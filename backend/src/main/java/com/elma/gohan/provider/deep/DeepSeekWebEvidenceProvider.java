package com.elma.gohan.provider.deep;

import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 消费 DeepSeek 搜索 API 的索引结果；失败时在同一期限内回退到现有搜索。 */
@Component
@Primary
@ConditionalOnProperty(prefix = "elma.deep-evidence", name = "deepseek-enabled", havingValue = "true")
public class DeepSeekWebEvidenceProvider implements DeepEvidenceProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekWebEvidenceProvider.class);

    private final DeepEvidenceProperties properties;
    private final WebEvidenceMatcher matcher;
    private final DeepEvidenceProvider fallback;
    private final String apiKey;
    private final RestClient restClient;

    @Autowired
    public DeepSeekWebEvidenceProvider(DeepEvidenceProperties properties,
                                       WebEvidenceMatcher matcher,
                                       BraveWebEvidenceProvider fallback) {
        this(properties, matcher, fallback, System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""));
    }

    DeepSeekWebEvidenceProvider(DeepEvidenceProperties properties,
                                WebEvidenceMatcher matcher,
                                DeepEvidenceProvider fallback, String apiKey) {
        this.properties = properties;
        this.matcher = matcher;
        this.fallback = fallback;
        this.apiKey = apiKey;
        restClient = RestClient.builder().baseUrl(properties.getDeepseekBaseUrl()).build();
    }

    @Override
    public DeepEvidenceBatch fetch(DeepEvidenceSource source, Restaurant restaurant) {
        return fetch(source, restaurant, System.nanoTime() + properties.getOverallTimeoutMs() * 1_000_000L);
    }

    @Override
    public DeepEvidenceBatch fetch(DeepEvidenceSource source, Restaurant restaurant, long deadlineNanos) {
        Instant now = Instant.now();
        deadlineNanos = Math.min(deadlineNanos,
                System.nanoTime() + properties.getOverallTimeoutMs() * 1_000_000L);
        if (!properties.isDeepseekEnabled() || apiKey.isBlank())
            return fallback.fetch(source, restaurant, deadlineNanos);
        // 为现有搜索保留一半剩余预算，回退不重新开启四秒期限。
        long deepseekDeadline = System.nanoTime() + Math.max(0, deadlineNanos - System.nanoTime()) / 2;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (System.nanoTime() >= deepseekDeadline || Thread.currentThread().isInterrupted())
                return fallback.fetch(source, restaurant, deadlineNanos);
            try {
                var found = search(source, restaurant, now, attempt != 0,
                        attempt == 0 ? "EXACT_LOCATION" : "BRAND_LOCATION", deepseekDeadline);
                if (!found.isEmpty()) return new DeepEvidenceBatch(source, EvidenceStatus.AVAILABLE, found, now);
            } catch (RuntimeException e) {
                // JDK HTTP timeouts can also escape as CancellationException, without Spring wrapping.
                log.warn("DeepSeek search attempt failed source={} attempt={} errorType={}",
                        source, attempt, e.getClass().getSimpleName());
                return fallback.fetch(source, restaurant, deadlineNanos);
            }
        }
        return fallback.fetch(source, restaurant, deadlineNanos);
    }

    private List<WebEvidenceItem> search(DeepEvidenceSource source, Restaurant restaurant,
                                         Instant fetchedAt, boolean fallbackQuery, String phase, long deadlineNanos) {
        long started = System.nanoTime();
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) throw new RestClientException("Deep evidence deadline exceeded");
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1,
                        Math.min(remaining, properties.getConnectTimeoutMs())))).build());
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, Math.min(remaining, properties.getReadTimeoutMs()))));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", truncate(buildQuery(source, restaurant, fallbackQuery), 128));
        request.put("count", Math.min(50, Math.max(1, properties.getResultCount())));
        request.put("need_content", false);
        JsonNode body = restClient.mutate().requestFactory(factory).build()
                .post().uri("/v1/tools/search").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(request).retrieve().body(JsonNode.class);
        JsonNode results = body == null ? null : body.path("results");
        if (results == null || !results.isArray()) throw new RestClientException("Invalid search response");
        List<WebEvidenceItem> items = map(source, restaurant, results, fetchedAt);
        log.info("DeepSeek search source={} phase={} rawResultCount={} matchedResultCount={} durationMs={}",
                source, phase, results.size(), items.size(), (System.nanoTime() - started) / 1_000_000);
        return items;
    }

    private String buildQuery(DeepEvidenceSource source, Restaurant restaurant, boolean fallbackQuery) {
        String safeName = restaurant.name().replace('"', ' ').trim();
        String location = matcher.searchLocationKeyword(restaurant.name(), restaurant.address());
        if (!properties.isImprovedSearchEnabled())
            return (source.siteQuery() + " \"" + safeName + "\" " + location).trim();
        String term = fallbackQuery ? com.elma.gohan.provider.evidence.EntityResolver.brandName(safeName) : safeName;
        return (source.siteQuery() + " " + term + " " + properties.getCity() + " " + location).trim();
    }

    private List<WebEvidenceItem> map(DeepEvidenceSource source, Restaurant restaurant,
                                      JsonNode results, Instant fetchedAt) {
        Map<String, WebEvidenceItem> deduplicated = new LinkedHashMap<>();
        Instant oldest = properties.getDeepseekMaxAgeDays() > 0
                ? fetchedAt.minus(properties.getDeepseekMaxAgeDays(), ChronoUnit.DAYS) : null;
        for (JsonNode result : results) {
            JsonNode score = result.path("rerank_score");
            if (properties.getDeepseekMinRerankScore() > 0
                    && (!score.isNumber() || score.asDouble() < properties.getDeepseekMinRerankScore())) continue;
            String date = text(result, "date");
            Instant publishedAt = date == null ? null : OffsetDateTime.parse(date).toInstant();
            if (oldest != null && publishedAt != null && publishedAt.isBefore(oldest)) continue;
            String title = truncate(text(result, "title"), 200);
            String url = canonicalUrl(source, text(result, "url"));
            String snippet = text(result, "snippet");
            if (snippet == null) snippet = text(result, "summary");
            if (snippet == null) snippet = text(result, "content");
            snippet = truncate(snippet, 500);
            if (title == null || url == null) continue;
            double matchConfidence = matcher.match(restaurant, title, snippet);
            if (matchConfidence < properties.getEntityMatchThreshold()) continue;
            WebEvidenceItem item = new WebEvidenceItem(source, title, url, snippet,
                    publishedAt, fetchedAt, matchConfidence, List.of());
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
            String domain = switch (source) {
                case BILIBILI -> "bilibili.com";
                case XIAOHONGSHU -> "xiaohongshu.com";
                case DIANPING -> "dianping.com";
            };
            if (!host.equals(domain) && !host.endsWith("." + domain)) return null;
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            String canonical = URI.create("https://" + host + path).toASCIIString();
            return canonical.length() <= 1000 ? canonical : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.codePointCount(0, value.length()) <= max
                ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
}
