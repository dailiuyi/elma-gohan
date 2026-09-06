package com.elma.gohan.provider.deep;

import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 消费正式搜索 API 的索引结果；兼容生产百度 AI 搜索和 Brave，不访问结果网页。 */
@Component
public class BraveWebEvidenceProvider implements DeepEvidenceProvider {

    private static final Logger log = LoggerFactory.getLogger(BraveWebEvidenceProvider.class);

    private final DeepEvidenceProperties properties;
    private final WebEvidenceMatcher matcher;
    private final RestClient restClient;

    public BraveWebEvidenceProvider(DeepEvidenceProperties properties,
                                    WebEvidenceMatcher matcher) {
        this.properties = properties;
        this.matcher = matcher;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        restClient = RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(factory).build();
        if (properties.isEnabled() && properties.getApiKey().isBlank()) {
            log.warn("Deep search API key missing; deep evidence is UNAVAILABLE");
        }
    }

    @Override
    public DeepEvidenceBatch fetch(DeepEvidenceSource source, Restaurant restaurant) {
        return fetch(source, restaurant, System.nanoTime() + properties.getOverallTimeoutMs() * 1_000_000L);
    }

    @Override
    public DeepEvidenceBatch fetch(DeepEvidenceSource source, Restaurant restaurant, long deadlineNanos) {
        Instant now = Instant.now();
        if (!properties.isEnabled() || properties.getApiKey().isBlank()) return DeepEvidenceBatch.unavailable(source, now);
        boolean failed = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (System.nanoTime() >= deadlineNanos || Thread.currentThread().isInterrupted()) {
                failed = true;
                break;
            }
            try {
                var found = search(source, restaurant, now, attempt == 0,
                        attempt == 0 ? "EXACT_LOCATION" : "BRAND_LOCATION", deadlineNanos);
                if (!found.items().isEmpty()) return new DeepEvidenceBatch(source, EvidenceStatus.AVAILABLE, found.items(), now);
            } catch (RestClientException e) {
                failed = true;
                log.warn("Deep search attempt failed source={} attempt={} errorType={}", source, attempt, e.getClass().getSimpleName());
            }
        }
        return new DeepEvidenceBatch(source, failed ? EvidenceStatus.UNAVAILABLE : EvidenceStatus.NO_DATA, List.of(), now);
    }

    private SearchAttempt search(DeepEvidenceSource source, Restaurant restaurant,
                                 Instant fetchedAt, boolean recentOnly, String phase, long deadlineNanos) {
        long started = System.nanoTime();
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) throw new RestClientException("Deep evidence deadline exceeded");
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(1,
                        Math.min(remaining, properties.getConnectTimeoutMs())))).build());
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, Math.min(remaining, properties.getReadTimeoutMs()))));
        JsonNode body;
        if (properties.isBaiduAiSearchEnabled()) {
            String domain = source.siteQuery().replace("site:", "").split("/")[0].trim();
            var resources = new java.util.ArrayList<Map<String, Object>>();
            int count = Math.min(20, Math.max(1, properties.getResultCount()));
            resources.add(Map.of("type", "web", "top_k", count));
            if (source == DeepEvidenceSource.BILIBILI) resources.add(Map.of("type", "video", "top_k", count));
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("messages", List.of(Map.of("role", "user", "content",
                    buildQuery(source, restaurant, !recentOnly).replace(source.siteQuery(), "").trim())));
            request.put("search_source", "baidu_search_v2");
            request.put("edition", "turbo");
            request.put("resource_type_filter", resources);
            request.put("search_filter", Map.of("match", Map.of("site", List.of(domain))));
            if (recentOnly && !properties.isImprovedSearchEnabled()) request.put("search_recency_filter", "month");
            body = restClient.mutate().baseUrl(properties.getBaiduAiBaseUrl()).requestFactory(factory).build()
                    .post().uri("/v2/ai_search/web_search").contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("X-Appbuilder-Authorization", "Bearer " + properties.getApiKey())
                    .body(request).retrieve().body(JsonNode.class);
            if (body != null && body.has("code") && !"0".equals(body.path("code").asText()))
                throw new RestClientException("Baidu AI search business failure");
        } else {
        body = restClient.mutate().requestFactory(factory).build().get().uri(uriBuilder -> {
                    var builder = uriBuilder.path("/res/v1/web/search")
                            .queryParam("q", buildQuery(source, restaurant, !recentOnly))
                            .queryParam("count", Math.min(20, Math.max(1, properties.getResultCount())))
                            .queryParam("offset", 0)
                            .queryParam("country", "CN")
                            .queryParam("search_lang", "zh-hans")
                            .queryParam("ui_lang", "zh-CN")
                            .queryParam("safesearch", "moderate")
                            .queryParam("spellcheck", false)
                            .queryParam("extra_snippets", properties.isImprovedSearchEnabled());
                    if (recentOnly && !properties.isImprovedSearchEnabled()) builder.queryParam("freshness", "pm");
                    return builder.build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Subscription-Token", properties.getApiKey())
                .retrieve().body(JsonNode.class);
        }
        JsonNode results = body == null ? null : properties.isBaiduAiSearchEnabled()
                ? body.path("references") : body.path("web").path("results");
        if (results == null || !results.isArray()) throw new RestClientException("Invalid search response");
        int rawResultCount = results != null && results.isArray() ? results.size() : 0;
        List<WebEvidenceItem> items = map(source, restaurant, results, fetchedAt);
        log.info("Deep search source={} phase={} rawResultCount={} matchedResultCount={} durationMs={}",
                source, phase, rawResultCount, items.size(), elapsedMillis(started));
        return new SearchAttempt(items);
    }

    private String validationField(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "none";
        try {
            JsonNode location = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(responseBody).path("error").path("meta")
                    .path("errors").path(0).path("loc");
            return location.isArray() && !location.isEmpty()
                    ? location.path(location.size() - 1).asText("unknown") : "unknown";
        } catch (Exception ignored) {
            return "unparseable";
        }
    }

    private String buildQuery(DeepEvidenceSource source, Restaurant restaurant, boolean fallback) {
        String safeName = restaurant.name().replace('"', ' ').trim();
        String location = matcher.searchLocationKeyword(restaurant.name(), restaurant.address());
        if (!properties.isImprovedSearchEnabled())
            return (source.siteQuery() + " \"" + safeName + "\" " + location).trim();
        String term = fallback ? com.elma.gohan.provider.evidence.EntityResolver.brandName(safeName) : safeName;
        return (source.siteQuery() + " " + term + " " + properties.getCity() + " " + location).trim();
    }

    private record SearchAttempt(List<WebEvidenceItem> items) { }

    private List<WebEvidenceItem> map(DeepEvidenceSource source, Restaurant restaurant,
                                      JsonNode results, Instant fetchedAt) {
        if (results == null || !results.isArray()) return List.of();
        Map<String, WebEvidenceItem> deduplicated = new LinkedHashMap<>();
        for (JsonNode result : results) {
            String title = truncate(text(result, "title"), 200);
            String rawUrl = text(result, "url");
            String snippet = truncate(text(result, "description"), 500);
            if (properties.isBaiduAiSearchEnabled() && text(result, "content") != null)
                snippet = truncate(text(result, "content"), 2500);
            if (properties.isImprovedSearchEnabled() && result.path("extra_snippets").isArray()) {
                StringBuilder context = new StringBuilder(snippet == null ? "" : snippet);
                int count = 0;
                for (JsonNode extra : result.path("extra_snippets")) {
                    if (++count > 5) break;
                    context.append(" ").append(truncate(extra.asText(""), 500));
                }
                snippet = context.toString();
            }
            String url = canonicalUrl(source, rawUrl);
            if (title == null || url == null) continue;
            double matchConfidence = matcher.match(restaurant, title, snippet);
            if (matchConfidence <= 0.0) continue;
            WebEvidenceItem item = new WebEvidenceItem(source, title, url, snippet,
                    parsePublishedAt(result), fetchedAt, matchConfidence, List.of());
            deduplicated.putIfAbsent(url, item);
        }
        return List.copyOf(deduplicated.values());
    }

    private String canonicalUrl(DeepEvidenceSource source, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowedHost(source, host)) return null;
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank()
                    ? "/" : uri.getRawPath();
            String canonical = URI.create("https://" + host + path).toASCIIString();
            return canonical.length() <= 1000 ? canonical : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean allowedHost(DeepEvidenceSource source, String host) {
        String domain = switch (source) {
            case BILIBILI -> "bilibili.com";
            case XIAOHONGSHU -> "xiaohongshu.com";
            case DIANPING -> "dianping.com";
        };
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private Instant parsePublishedAt(JsonNode result) {
        for (String field : List.of("page_age", "published_at", "date")) {
            String value = text(result, field);
            if (value == null) continue;
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.parse(value).toInstant();
                } catch (DateTimeParseException ignoredOffset) {
                    try {
                        return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
                    } catch (DateTimeParseException ignoredDateTime) {
                        try {
                            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
                        } catch (DateTimeParseException ignoredDate) {
                            // 相对时间或非标准日期不猜测。
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
