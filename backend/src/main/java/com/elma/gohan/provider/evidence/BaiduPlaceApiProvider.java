package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 百度 Place V3/V2 正规 API；完整 URL 含 AK，禁止写入日志。 */
@Component
public class BaiduPlaceApiProvider implements PlatformEvidenceProvider {

    private static final Logger log = LoggerFactory.getLogger(BaiduPlaceApiProvider.class);
    private static final String SOURCE = "BAIDU";

    private final BaiduProperties properties;
    private final RestClient restClient;
    private final BaiduPlaceRateLimiter rateLimiter;

    public BaiduPlaceApiProvider(BaiduProperties properties,
                                 BaiduPlaceRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(factory).build();
        if (properties.isEnabled() && properties.getAk().isBlank()) {
            log.warn("BAIDU_MAP_AK 未配置，百度 Evidence 将降级为 UNAVAILABLE");
        }
    }

    @Override
    public PlatformSearchResult searchV3(Location center, int radiusMeters, int pageNumber) {
        return search("V3", "/place/v3/around", center, radiusMeters, pageNumber,
                properties.getQuery(), true, false);
    }

    @Override
    public PlatformSearchResult searchV3(Location center, int radiusMeters, int pageNumber,
                                         String query) {
        String safeQuery = query == null || query.isBlank() ? properties.getQuery() : query;
        return search("V3", "/place/v3/around", center, radiusMeters, pageNumber,
                safeQuery, true, false);
    }

    @Override
    public PlatformSearchResult searchNearby(Location center, int radiusMeters, String query) {
        String safeQuery = query == null || query.isBlank() ? properties.getQuery() : query;
        return search("V3", "/place/v3/around", center, radiusMeters, 0,
                safeQuery, true, true);
    }

    @Override
    public PlatformSearchResult searchRegion(String query, String region) {
        if (!properties.isEnabled() || properties.getAk().isBlank()) {
            return PlatformSearchResult.unavailable();
        }
        String safeQuery = query == null || query.isBlank() ? properties.getQuery() : query;
        String safeRegion = region == null || region.isBlank() ? "长沙" : region;
        long started = System.nanoTime();
        int pageSize = Math.min(20, Math.max(10, properties.getPageSize()));
        if (!acquirePermit("REGION")) return PlatformSearchResult.unavailable();
        try {
            JsonNode body = restClient.get().uri(uriBuilder -> uriBuilder.path("/place/v3/region")
                    .queryParam("query", safeQuery)
                    .queryParam("region", safeRegion)
                    .queryParam("city_limit", true)
                    .queryParam("scope", 2)
                    .queryParam("ret_coordtype", "gcj02ll")
                    .queryParam("page_size", pageSize)
                    .queryParam("page_num", 0)
                    .queryParam("output", "json")
                    .queryParam("ak", properties.getAk())
                    .build()).retrieve().body(JsonNode.class);
            if (body == null || body.path("status").asInt(-1) != 0) {
                int status = body == null ? -1 : body.path("status").asInt(-1);
                log.warn("百度 Place REGION 返回失败状态 status={} durationMs={}",
                        status, elapsedMillis(started));
                return PlatformSearchResult.unavailable();
            }
            List<PlatformEvidence> evidence = mapResults(body.path("results"), Instant.now());
            Integer total = integer(body, "total");
            String querySummary = safeQuery.substring(0, Math.min(safeQuery.length(), 40));
            log.info("百度 Place REGION 完成 query={} resultCount={} total={} durationMs={}",
                    querySummary, evidence.size(), total, elapsedMillis(started));
            return new PlatformSearchResult(evidence.isEmpty()
                    ? EvidenceStatus.NO_DATA : EvidenceStatus.AVAILABLE, evidence,
                    total, 0, pageSize);
        } catch (RestClientException exception) {
            log.warn("百度 Place REGION 请求失败 durationMs={} errorType={}",
                    elapsedMillis(started), exception.getClass().getSimpleName());
            return PlatformSearchResult.unavailable();
        }
    }

    @Override
    public PlatformSearchResult searchSuggestion(Location center, String query, String region) {
        if (!properties.isEnabled() || properties.getAk().isBlank()) {
            return PlatformSearchResult.unavailable();
        }
        String safeQuery = query == null || query.isBlank() ? properties.getQuery() : query;
        String safeRegion = region == null || region.isBlank() ? "长沙" : region;
        long started = System.nanoTime();
        if (!acquirePermit("SUGGEST")) return PlatformSearchResult.unavailable();
        try {
            JsonNode body = restClient.get().uri(uriBuilder -> {
                var builder = uriBuilder.path("/place/v2/suggestion")
                        .queryParam("query", safeQuery)
                        .queryParam("region", safeRegion)
                        .queryParam("city_limit", true)
                        .queryParam("coord_type", 2)
                        .queryParam("ret_coordtype", "gcj02ll")
                        .queryParam("output", "json")
                        .queryParam("ak", properties.getAk());
                if (center != null) {
                    builder.queryParam("location", center.latitude() + "," + center.longitude());
                }
                return builder.build();
            }).retrieve().body(JsonNode.class);
            if (body == null || body.path("status").asInt(-1) != 0) {
                int status = body == null ? -1 : body.path("status").asInt(-1);
                log.warn("百度 Place SUGGEST 返回失败状态 status={} durationMs={}",
                        status, elapsedMillis(started));
                return PlatformSearchResult.unavailable();
            }
            List<PlatformEvidence> evidence = mapSuggestionResults(body.path("result"), Instant.now());
            String querySummary = safeQuery.substring(0, Math.min(safeQuery.length(), 40));
            log.info("百度 Place SUGGEST 完成 query={} resultCount={} durationMs={}",
                    querySummary, evidence.size(), elapsedMillis(started));
            return new PlatformSearchResult(evidence.isEmpty()
                    ? EvidenceStatus.NO_DATA : EvidenceStatus.AVAILABLE, evidence,
                    evidence.size(), 0, evidence.size());
        } catch (RestClientException exception) {
            log.warn("百度 Place SUGGEST 请求失败 durationMs={} errorType={}",
                    elapsedMillis(started), exception.getClass().getSimpleName());
            return PlatformSearchResult.unavailable();
        }
    }

    @Override
    public PlatformSearchResult searchV2(Location center, int radiusMeters) {
        return search("V2", "/place/v2/search", center, radiusMeters, 0,
                properties.getQuery(), true, false);
    }

    private PlatformSearchResult search(String apiVersion, String path, Location center,
                                        int radiusMeters, int pageNumber, String query,
                                        boolean radiusLimit, boolean sortByDistance) {
        if (!properties.isEnabled() || properties.getAk().isBlank()) {
            return PlatformSearchResult.unavailable();
        }
        long started = System.nanoTime();
        int safePageNumber = Math.max(0, pageNumber);
        int pageSize = Math.min(20, Math.max(10, properties.getPageSize()));
        if (!acquirePermit(apiVersion)) return PlatformSearchResult.unavailable();
        try {
            JsonNode body = restClient.get().uri(uriBuilder -> {
                var builder = uriBuilder.path(path)
                        .queryParam("query", query)
                        .queryParam("location", center.latitude() + "," + center.longitude())
                        .queryParam("radius", radiusMeters)
                        .queryParam("radius_limit", radiusLimit)
                        .queryParam("scope", 2)
                        .queryParam("coord_type", 2)
                        .queryParam("ret_coordtype", "gcj02ll")
                        .queryParam("page_size", pageSize)
                        .queryParam("page_num", safePageNumber)
                        .queryParam("output", "json")
                        .queryParam("ak", properties.getAk());
                if (sortByDistance) {
                    builder.queryParam("filter",
                            "industry_type:cater|sort_name:distance|sort_rule:1");
                }
                return builder.build();
            }).retrieve().body(JsonNode.class);
            if (body == null || body.path("status").asInt(-1) != 0) {
                int status = body == null ? -1 : body.path("status").asInt(-1);
                log.warn("百度 Place {} 返回失败状态 page={} status={} durationMs={}", apiVersion,
                        safePageNumber, status, elapsedMillis(started));
                return PlatformSearchResult.unavailable();
            }
            List<PlatformEvidence> evidence = mapResults(body.path("results"), Instant.now());
            Integer total = integer(body, "total");
            String querySummary = query == null ? "" : query.substring(0, Math.min(query.length(), 40));
            log.info("百度 Place {} 完成 query={} page={} resultCount={} total={} durationMs={}",
                    apiVersion, querySummary, safePageNumber, evidence.size(), total, elapsedMillis(started));
            return new PlatformSearchResult(evidence.isEmpty()
                    ? EvidenceStatus.NO_DATA : EvidenceStatus.AVAILABLE, evidence,
                    total, safePageNumber, pageSize);
        } catch (RestClientException exception) {
            log.warn("百度 Place {} 请求失败 page={} durationMs={} errorType={}", apiVersion,
                    safePageNumber, elapsedMillis(started), exception.getClass().getSimpleName());
            return PlatformSearchResult.unavailable();
        }
    }

    private List<PlatformEvidence> mapResults(JsonNode results, Instant observedAt) {
        if (!results.isArray()) return List.of();
        List<PlatformEvidence> mapped = new ArrayList<>();
        for (JsonNode result : results) {
            String uid = text(result, "uid");
            String name = text(result, "name");
            if (uid == null || name == null) continue;
            JsonNode location = result.path("location");
            JsonNode detail = result.path("detail_info");
            mapped.add(new PlatformEvidence(SOURCE, uid, EvidenceStatus.AVAILABLE, observedAt,
                    name, text(result, "address"), decimal(location, "lat"),
                    decimal(location, "lng"), decimal(detail, "overall_rating"),
                    decimal(detail, "taste_rating"), decimal(detail, "service_rating"),
                    decimal(detail, "environment_rating"), integer(detail, "comment_num"),
                    integer(detail, "price"), text(detail, "shop_hours"),
                    text(detail, "brand"), text(result, "telephone")));
        }
        return List.copyOf(mapped);
    }

    private List<PlatformEvidence> mapSuggestionResults(JsonNode results, Instant observedAt) {
        if (!results.isArray()) return List.of();
        List<PlatformEvidence> mapped = new ArrayList<>();
        for (JsonNode result : results) {
            String uid = text(result, "uid");
            String name = text(result, "name");
            if (uid == null || name == null) continue;
            JsonNode location = result.path("location");
            if (!location.isObject()) continue;
            Double latitude = decimal(location, "lat");
            Double longitude = decimal(location, "lng");
            if (latitude == null || longitude == null) continue;
            mapped.add(new PlatformEvidence(SOURCE, uid, EvidenceStatus.AVAILABLE, observedAt,
                    name, suggestionAddress(result), latitude, longitude,
                    null, null, null, null, null, null, null, null, null));
        }
        return List.copyOf(mapped);
    }

    private static String suggestionAddress(JsonNode result) {
        String address = text(result, "address");
        if (address != null) return address;
        String district = text(result, "district");
        String business = text(result, "business");
        if (district == null) return business;
        if (business == null) return district;
        return district + business;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static Double decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try {
            double parsed = Double.parseDouble(value.replaceAll("[^0-9.-]", ""));
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integer(JsonNode node, String field) {
        Double value = decimal(node, field);
        return value == null ? null : (int) Math.round(value);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private boolean acquirePermit(String operation) {
        BaiduPlaceRateLimiter.Permit permit = rateLimiter.acquire();
        if (!permit.acquired()) {
            log.warn("百度 Place 全局节流拒绝 operation={} waitedMs={}",
                    operation, permit.waitedMillis());
            return false;
        }
        if (permit.waitedMillis() > 0) {
            log.debug("百度 Place 全局节流等待 operation={} waitedMs={}",
                    operation, permit.waitedMillis());
        }
        return true;
    }
}
