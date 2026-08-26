package com.elma.gohan.provider.poi.amap;

import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.provider.poi.PoiProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 高德 Web Service 周边搜索客户端。Key 只来自 AmapProperties(环境变量 AMAP_KEY),
 * 请求 URL 含 Key,因此禁止把 URL 记入日志。
 */
@Component
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    private final AmapProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AmapClient(AmapProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
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

    /**
     * 按用户品类召回，并在距离排序下穿过环带下限；达到候选目标、结果耗尽或 maxPages 后停止。
     */
    public AmapSearchResult searchAround(double latitude, double longitude,
                                         SearchCondition condition) {
        if (props.getKey() == null || props.getKey().isBlank()) {
            throw new PoiProviderException("AMAP key 未配置");
        }
        String queryTypes = props.searchTypesFor(condition.category());
        String queryKeyword = props.searchKeywordFor(condition.category());
        List<JsonNode> pois = new ArrayList<>();
        Set<String> seenPoiIds = new HashSet<>();
        Integer providerTotalCount = null;
        Integer lastFetchedDistance = null;
        int fetchedCount = 0;
        int pagesFetched = 0;
        int inBandCandidateCount = 0;
        boolean exhausted = false;
        try {
            for (int page = 1; page <= props.getMaxPages(); page++) {
                final int currentPage = page;
                JsonNode body = restClient.get()
                        .uri(uriBuilder -> {
                            var builder = uriBuilder.path("/v3/place/around")
                                    .queryParam("key", props.getKey())
                                    .queryParam("location", longitude + "," + latitude)
                                    .queryParam("radius", condition.radius())
                                    .queryParam("types", queryTypes)
                                    .queryParam("sortrule", "distance")
                                    .queryParam("extensions", "all")
                                    .queryParam("offset", props.getPageSize())
                                    .queryParam("page", currentPage);
                            if (queryKeyword != null) {
                                builder.queryParam("keywords", queryKeyword);
                            }
                            return builder.build();
                        })
                        .retrieve()
                        .body(JsonNode.class);
                if (body == null || !"1".equals(body.path("status").asText())) {
                    throw new PoiProviderException("高德周边搜索返回失败状态");
                }
                pagesFetched++;
                Integer pageTotalCount = parseNullableInt(body.path("count").asText(null));
                if (pageTotalCount != null) {
                    providerTotalCount = providerTotalCount == null
                            ? pageTotalCount : Math.max(providerTotalCount, pageTotalCount);
                }
                JsonNode pagePois = body.path("pois");
                if (!pagePois.isArray()) {
                    exhausted = true;
                    break;
                }
                for (JsonNode poi : pagePois) {
                    fetchedCount++;
                    Integer distance = parseNullableInt(poi.path("distance").asText(null));
                    if (distance != null) {
                        lastFetchedDistance = distance;
                    }
                    String poiId = poi.path("id").asText("").trim();
                    if (!poiId.isBlank() && !seenPoiIds.add(poiId)) {
                        continue;
                    }
                    pois.add(poi);
                    if (distance != null && (condition.minDistance() == null
                            || distance > condition.minDistance())) {
                        inBandCandidateCount++;
                    }
                }
                if (pagePois.size() < props.getPageSize()
                        || (providerTotalCount != null && fetchedCount >= providerTotalCount)) {
                    exhausted = true;
                    break;
                }
                boolean crossedLowerBound = condition.minDistance() == null
                        || (lastFetchedDistance != null
                        && lastFetchedDistance > condition.minDistance());
                if (crossedLowerBound && inBandCandidateCount >= props.getTargetCandidates()) {
                    break;
                }
            }
        } catch (RestClientException e) {
            log.warn("高德周边搜索请求失败: {}", e.getMessage());
            throw new PoiProviderException("高德周边搜索请求失败", e);
        }
        boolean truncated = !exhausted
                && (providerTotalCount == null || fetchedCount < providerTotalCount);
        return new AmapSearchResult(pois, providerTotalCount, fetchedCount, pagesFetched,
                truncated, lastFetchedDistance, queryTypes, queryKeyword);
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
}
