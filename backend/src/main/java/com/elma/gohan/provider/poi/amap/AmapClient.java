package com.elma.gohan.provider.poi.amap;

import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.provider.poi.PoiProviderException;
import com.elma.gohan.provider.poi.PoiSearchCompletionReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 高德 Web Service 周边搜索客户端。Key 只来自 AmapProperties(环境变量 AMAP_KEY),
 * 请求 URL 含 Key,因此禁止把 URL、异常原始消息和精确坐标记入日志。
 */
@Component
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
    private static final Set<String> RETRYABLE_INFOCODES = Set.of(
            "10014", "10015", "10016", "10017",
            "10019", "10020", "10021", "10022", "10023");

    private final AmapProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AmapRateLimiter rateLimiter;

    @Autowired
    public AmapClient(AmapProperties props, ObjectMapper objectMapper,
                      AmapRateLimiter rateLimiter) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        if (props.getKey() == null || props.getKey().isBlank()) {
            log.warn("AMAP_KEY 未配置,推荐接口将返回 502 POI_PROVIDER_UNAVAILABLE");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /** 兼容不启动 Spring 容器的既有测试。 */
    AmapClient(AmapProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, new AmapRateLimiter(props));
    }

    /**
     * 按用户品类召回，并在距离排序下穿过环带下限；达到候选目标、结果耗尽、
     * maxPages 或整次召回期限后停止。
     */
    public AmapSearchResult searchAround(double latitude, double longitude,
                                         SearchCondition condition) {
        if (props.getKey() == null || props.getKey().isBlank()) {
            throw new PoiProviderException("AMAP key 未配置");
        }
        long deadlineNanos = System.nanoTime()
                + Duration.ofMillis(props.getRecallDeadlineMs()).toNanos();
        String queryTypes = props.searchTypesFor(condition.category());
        String queryKeyword = props.searchKeywordFor(condition.category());
        List<JsonNode> pois = new ArrayList<>();
        Set<String> seenPoiIds = new HashSet<>();
        Integer providerTotalCount = null;
        Integer lastFetchedDistance = null;
        int fetchedCount = 0;
        int pagesFetched = 0;
        int inBandCandidateCount = 0;
        int retryCount = 0;
        PoiSearchCompletionReason completionReason = null;

        for (int page = 1; page <= props.getMaxPages(); page++) {
            PageFetch fetch = fetchPage(latitude, longitude, condition, queryTypes,
                    queryKeyword, page, deadlineNanos);
            retryCount += fetch.retryCount();
            if (fetch.body() == null) {
                if (pagesFetched == 0) {
                    throw new PoiProviderException("高德周边搜索暂时不可用");
                }
                completionReason = fetch.deadlineReached()
                        ? PoiSearchCompletionReason.RECALL_DEADLINE_REACHED
                        : PoiSearchCompletionReason.UPSTREAM_RETRY_EXHAUSTED;
                break;
            }

            JsonNode body = fetch.body();
            pagesFetched++;
            Integer pageTotalCount = parseNullableInt(body.path("count").asText(null));
            if (pageTotalCount != null) {
                providerTotalCount = providerTotalCount == null
                        ? pageTotalCount : Math.max(providerTotalCount, pageTotalCount);
            }
            JsonNode pagePois = body.path("pois");
            if (!pagePois.isArray()) {
                completionReason = PoiSearchCompletionReason.RESULTS_EXHAUSTED;
                break;
            }
            for (JsonNode poi : pagePois) {
                fetchedCount++;
                Integer distance = parseNullableInt(poi.path("distance").asText(null));
                if (distance != null) lastFetchedDistance = distance;
                String poiId = poi.path("id").asText("").trim();
                if (!poiId.isBlank() && !seenPoiIds.add(poiId)) continue;
                pois.add(poi);
                if (distance != null && (condition.minDistance() == null
                        || distance > condition.minDistance())) {
                    inBandCandidateCount++;
                }
            }
            if (pagePois.size() < props.getPageSize()
                    || (providerTotalCount != null && fetchedCount >= providerTotalCount)) {
                completionReason = PoiSearchCompletionReason.RESULTS_EXHAUSTED;
                break;
            }
            boolean crossedLowerBound = condition.minDistance() == null
                    || (lastFetchedDistance != null
                    && lastFetchedDistance > condition.minDistance());
            if (crossedLowerBound && inBandCandidateCount >= props.getTargetCandidates()) {
                completionReason = PoiSearchCompletionReason.TARGET_REACHED;
                break;
            }
        }
        if (completionReason == null) {
            completionReason = System.nanoTime() >= deadlineNanos
                    ? PoiSearchCompletionReason.RECALL_DEADLINE_REACHED
                    : PoiSearchCompletionReason.MAX_PAGES_REACHED;
        }
        boolean truncated = completionReason.incomplete();
        log.info("高德召回结束 traceId={} radius={} minDistance={} category={} pages={} retries={} "
                        + "fetched={} deduplicated={} inBand={} completionReason={} complete={}",
                traceId(), condition.radius(), condition.minDistance(), condition.category(),
                pagesFetched, retryCount, fetchedCount, pois.size(), inBandCandidateCount,
                completionReason, !truncated);
        return new AmapSearchResult(pois, providerTotalCount, fetchedCount, pagesFetched,
                truncated, lastFetchedDistance, queryTypes, queryKeyword,
                completionReason, retryCount);
    }

    private PageFetch fetchPage(double latitude, double longitude, SearchCondition condition,
                                String queryTypes, String queryKeyword, int page,
                                long deadlineNanos) {
        int maxAttempts = Math.max(1, props.getMaxRetries() + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            AmapRateLimiter.Permit permit = rateLimiter.acquire(deadlineNanos);
            if (!permit.acquired()) {
                return new PageFetch(null, Math.max(0, attempt - 1), true);
            }
            long callStarted = System.nanoTime();
            try {
                JsonNode body = invoke(latitude, longitude, condition, queryTypes,
                        queryKeyword, page);
                long callMillis = elapsedMillis(callStarted);
                String status = body == null ? null : body.path("status").asText(null);
                String info = body == null ? null : body.path("info").asText(null);
                String infocode = body == null ? null : body.path("infocode").asText(null);
                if (body != null && "1".equals(status)) {
                    JsonNode pagePois = body.path("pois");
                    int count = pagePois.isArray() ? pagePois.size() : 0;
                    Integer lastDistance = pagePois.isArray() && count > 0
                            ? parseNullableInt(pagePois.get(count - 1).path("distance").asText(null))
                            : null;
                    log.info("高德请求成功 traceId={} page={} attempt={} radius={} minDistance={} "
                                    + "category={} limiterWaitMs={} callMs={} status={} info={} infocode={} "
                                    + "returned={} lastDistance={}",
                            traceId(), page, attempt, condition.radius(), condition.minDistance(),
                            condition.category(), permit.waitedMillis(), callMillis, status, info,
                            infocode, count, lastDistance);
                    if (attempt > 1) {
                        log.info("高德请求重试恢复 traceId={} page={} retries={}",
                                traceId(), page, attempt - 1);
                    }
                    return new PageFetch(body, attempt - 1, false);
                }
                boolean retryable = body == null
                        || (infocode != null && RETRYABLE_INFOCODES.contains(infocode));
                logProviderFailure(page, attempt, condition, permit.waitedMillis(), callMillis,
                        status, info, infocode, retryable);
                if (!retryable) {
                    throw new PoiProviderException("高德周边搜索配置或参数错误");
                }
                if (attempt == maxAttempts) {
                    return new PageFetch(null, attempt - 1, false);
                }
            } catch (RestClientResponseException e) {
                long callMillis = elapsedMillis(callStarted);
                int httpStatus = e.getStatusCode().value();
                boolean retryable = httpStatus == 429 || httpStatus >= 500;
                logTransportFailure(page, attempt, condition, permit.waitedMillis(), callMillis,
                        httpStatus, e.getClass().getSimpleName(), retryable);
                if (!retryable) {
                    throw new PoiProviderException("高德周边搜索配置或参数错误", e);
                }
                if (attempt == maxAttempts) {
                    return new PageFetch(null, attempt - 1, false);
                }
            } catch (ResourceAccessException e) {
                boolean retryable = isRetryableTransport(e);
                logTransportFailure(page, attempt, condition, permit.waitedMillis(),
                        elapsedMillis(callStarted), null, e.getClass().getSimpleName(), retryable);
                if (!retryable) {
                    throw new PoiProviderException("高德周边搜索连接配置错误", e);
                }
                if (attempt == maxAttempts) {
                    return new PageFetch(null, attempt - 1, false);
                }
            } catch (RestClientException e) {
                logTransportFailure(page, attempt, condition, permit.waitedMillis(),
                        elapsedMillis(callStarted), null, e.getClass().getSimpleName(), false);
                throw new PoiProviderException("高德周边搜索响应处理失败", e);
            }

            if (!backoff(attempt, deadlineNanos)) {
                return new PageFetch(null, attempt - 1, true);
            }
        }
        return new PageFetch(null, props.getMaxRetries(), false);
    }

    private static boolean isRetryableTransport(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return true;
            }
            if (current instanceof SocketException socketException) {
                String message = socketException.getMessage();
                if (message != null && message.toLowerCase(java.util.Locale.ROOT)
                        .contains("reset")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private JsonNode invoke(double latitude, double longitude, SearchCondition condition,
                            String queryTypes, String queryKeyword, int page) {
        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/v3/place/around")
                            .queryParam("key", props.getKey())
                            .queryParam("location", longitude + "," + latitude)
                            .queryParam("radius", condition.radius())
                            .queryParam("types", queryTypes)
                            .queryParam("sortrule", "distance")
                            .queryParam("extensions", "all")
                            .queryParam("offset", props.getPageSize())
                            .queryParam("page", page);
                    if (queryKeyword != null) builder.queryParam("keywords", queryKeyword);
                    return builder.build();
                })
                .retrieve()
                .body(JsonNode.class);
    }

    private boolean backoff(int failedAttempt, long deadlineNanos) {
        long baseMillis = (long) props.getRetryInitialBackoffMs()
                * (1L << Math.max(0, failedAttempt - 1));
        int jitterBound = Math.max(0, props.getRetryBackoffJitterMs());
        long jitter = jitterBound == 0 ? 0
                : ThreadLocalRandom.current().nextLong(-jitterBound, jitterBound + 1L);
        long waitNanos = Duration.ofMillis(Math.max(0, baseMillis + jitter)).toNanos();
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0 || waitNanos > remaining) return false;
        LockSupport.parkNanos(waitNanos);
        return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadlineNanos;
    }

    private void logProviderFailure(int page, int attempt, SearchCondition condition,
                                    long limiterWaitMs, long callMs, String status,
                                    String info, String infocode, boolean retryable) {
        String template = "高德请求失败 traceId={} page={} attempt={} radius={} minDistance={} "
                + "category={} limiterWaitMs={} callMs={} status={} info={} infocode={} retryable={}";
        if (retryable) {
            log.warn(template, traceId(), page, attempt, condition.radius(),
                    condition.minDistance(), condition.category(), limiterWaitMs, callMs,
                    status, info, infocode, true);
        } else {
            log.error(template, traceId(), page, attempt, condition.radius(),
                    condition.minDistance(), condition.category(), limiterWaitMs, callMs,
                    status, info, infocode, false);
        }
    }

    private void logTransportFailure(int page, int attempt, SearchCondition condition,
                                     long limiterWaitMs, long callMs, Integer httpStatus,
                                     String failureType, boolean retryable) {
        String template = "高德传输失败 traceId={} page={} attempt={} radius={} minDistance={} "
                + "category={} limiterWaitMs={} callMs={} httpStatus={} failureType={} retryable={}";
        if (retryable) {
            log.warn(template, traceId(), page, attempt, condition.radius(),
                    condition.minDistance(), condition.category(), limiterWaitMs, callMs,
                    httpStatus, failureType, true);
        } else {
            log.error(template, traceId(), page, attempt, condition.radius(),
                    condition.minDistance(), condition.category(), limiterWaitMs, callMs,
                    httpStatus, failureType, false);
        }
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "-" : value;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static Integer parseNullableInt(String value) {
        if (value == null || value.isBlank() || "[]".equals(value)) return null;
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    private record PageFetch(JsonNode body, int retryCount, boolean deadlineReached) {
    }
}
