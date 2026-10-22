package com.elma.gohan.provider.deep;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class DeepSeekWebEvidenceProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> braveRequests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private volatile String responseBody;
    private volatile int responseStatus = 200;
    private volatile long delayMs;
    private volatile int braveStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        responseBody = results(item("https://www.bilibili.com/video/1", 1.0, null));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/tools/search", this::respond);
        server.createContext("/res/v1/web/search", this::respondBrave);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void parsesResponseAndSendsDocumentedPostBody() throws Exception {
        String date = Instant.now().minus(2, ChronoUnit.DAYS).toString();
        responseBody = results(item("https://www.bilibili.com/video/1?track=1#part", 1.0, date));
        var result = fetch(properties());
        assertThat(result.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(result.items()).hasSize(1);
        var item = result.items().get(0);
        assertThat(item.source()).isEqualTo(DeepEvidenceSource.BILIBILI);
        assertThat(item.title()).isEqualTo("老王湘菜馆大学城店值得吃吗");
        assertThat(item.url()).isEqualTo("https://www.bilibili.com/video/1");
        assertThat(item.snippet()).isEqualTo("麓山南路，分量足但高峰期排队");
        assertThat(item.publishedAt()).isEqualTo(Instant.parse(date));
        assertThat(item.fetchedAt()).isBetween(Instant.now().minusSeconds(5), Instant.now());
        assertThat(item.entityMatchConfidence()).isGreaterThanOrEqualTo(0.72);
        assertThat(requests).hasSize(1);
        Request request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).isEqualTo("Bearer test-deepseek-key");
        assertThat(request.contentType()).startsWith("application/json");
        var body = objectMapper.readTree(request.body());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("query").asText()).isEqualTo("site:bilibili.com/video 老王湘菜馆 长沙 麓山南路");
        assertThat(body.path("count").asInt()).isEqualTo(10);
        assertThat(body.path("need_content").asBoolean()).isFalse();
        assertThat(braveRequests).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(DeepEvidenceSource.class)
    void usesSourceSiteQueryAndWhitelist(DeepEvidenceSource source) throws Exception {
        String domain = switch (source) {
            case BILIBILI -> "bilibili.com";
            case XIAOHONGSHU -> "xiaohongshu.com";
            case DIANPING -> "dianping.com";
        };
        responseBody = results(item("https://www." + domain + "/item/1", 2.0, null),
                item("https://" + domain + ".example.com/item/2", 3.0, null),
                item("http://www." + domain + "/item/3", 3.0, null));
        assertThat(provider(properties(), "test-key").fetch(source, restaurant()).items()).hasSize(1);
        assertThat(objectMapper.readTree(requests.get(0).body()).path("query").asText())
                .startsWith(source.siteQuery() + " ");
    }

    @Test
    void filtersScoreAgeAndStoreIdentityAndDeduplicates() {
        String recent = Instant.now().minus(3, ChronoUnit.DAYS).toString();
        String old = Instant.now().minus(10, ChronoUnit.DAYS).toString();
        responseBody = results(item("https://www.bilibili.com/video/recent?x=1", 1.5, recent),
                item("https://www.bilibili.com/video/recent?x=2", 3.0, recent),
                item("https://www.bilibili.com/video/undated", 2.0, null),
                item("https://www.bilibili.com/video/low", 1.49, recent),
                item("https://www.bilibili.com/video/old", 3.0, old),
                item("https://www.bilibili.com/video/wrong-store", 3.0, recent)
                        .replace("老王湘菜馆大学城店值得吃吗", "另一家店"),
                item("https://www.bilibili.com/video/brand-only", 3.0, recent)
                        .replace("麓山南路，分量足但高峰期排队", "好吃"),
                item("https://www.bilibili.com/video/no-score", 3.0, recent)
                        .replace("\"rerank_score\":3.0,", ""));
        var p = properties();
        p.setDeepseekMinRerankScore(1.5);
        p.setDeepseekMaxAgeDays(7);
        var result = fetch(p);
        assertThat(result.items()).extracting(WebEvidenceItem::url).containsExactly(
                "https://www.bilibili.com/video/recent", "https://www.bilibili.com/video/undated");
        assertThat(result.items().get(1).publishedAt()).isNull();
        assertThat(braveRequests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary", "content"})
    void usesSummaryOrContentWhenSnippetIsMissing(String field) {
        responseBody = results(item("https://www.bilibili.com/video/1", 2.0, null)
                .replace("\"snippet\":", "\"" + field + "\":"));
        assertThat(fetch(properties()).items().get(0).snippet()).contains("麓山南路");
    }

    @Test
    void acceptsRfc3339OffsetDate() {
        responseBody = results(item("https://www.bilibili.com/video/1", 2.0, "2026-10-21T12:00:00+08:00"));
        var p = properties();
        p.setDeepseekMaxAgeDays(36500);
        assertThat(fetch(p).items().get(0).publishedAt()).isEqualTo(Instant.parse("2026-10-21T04:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "100,50"})
    void truncatesQueryByUnicodeCodePointAndClampsCount(int requested, int expected) throws Exception {
        responseBody = "{\"results\":[]}";
        var p = properties();
        p.setResultCount(requested);
        provider(p, "test-key").fetch(DeepEvidenceSource.BILIBILI,
                TestRestaurants.full("a", "餐厅😀".repeat(80), 4.6, 500, 42));
        String fullQuery = "site:bilibili.com/video " + "餐厅😀".repeat(80) + " 长沙 麓山南路";
        var body = objectMapper.readTree(requests.get(0).body());
        assertThat(body.path("query").asText())
                .isEqualTo(fullQuery.substring(0, fullQuery.offsetByCodePoints(0, 128)));
        assertThat(body.path("count").asInt()).isEqualTo(expected);
    }

    @Test
    void retriesBrandQueryThenReturnsNoDataWithoutBrave() throws Exception {
        responseBody = "{\"results\":[]}";
        var result = provider(properties(), "test-key").fetch(DeepEvidenceSource.BILIBILI,
                TestRestaurants.full("a", "老王湘菜馆（大学城店）", 4.6, 500, 42));
        assertThat(result.status()).isEqualTo(EvidenceStatus.NO_DATA);
        assertThat(requests).hasSize(2);
        assertThat(objectMapper.readTree(requests.get(0).body()).path("query").asText())
                .isEqualTo("site:bilibili.com/video 老王湘菜馆（大学城店） 长沙 大学城");
        assertThat(objectMapper.readTree(requests.get(1).body()).path("query").asText())
                .isEqualTo("site:bilibili.com/video 老王 长沙 大学城");
        assertThat(braveRequests).isEmpty();
    }

    @Test
    void disabledFallsBackWithoutDeepSeekRequest() {
        var p = properties();
        p.setDeepseekEnabled(false);
        assertBraveResult(fetch(p));
        assertThat(requests).isEmpty();
    }

    @Test
    void missingKeyFallsBackWithoutDeepSeekRequest() {
        assertBraveResult(provider(properties(), "").fetch(DeepEvidenceSource.BILIBILI, restaurant()));
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 503})
    void httpFailureFallsBackToBrave(int status) {
        responseStatus = status;
        assertBraveResult(fetch(properties()));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"results\":null}", "{\"results\":{}}", "not-json", ""})
    void invalidResponseFallsBackToBrave(String body) {
        responseBody = body;
        assertBraveResult(fetch(properties()));
        assertThat(requests).hasSize(1);
    }

    @Test
    void invalidDateFallsBackToBrave() {
        responseBody = results(item("https://www.bilibili.com/video/1", 2.0, "not-a-date"));
        assertBraveResult(fetch(properties()));
    }

    @ParameterizedTest
    @MethodSource("uncheckedFailures")
    void uncheckedSearchFailureFallsBackToBrave(RuntimeException failure) {
        var p = properties();
        var matcher = new WebEvidenceMatcher(p) {
            @Override
            public double match(Restaurant restaurant, String title, String snippet) {
                throw failure;
            }
        };
        var brave = new BraveWebEvidenceProvider(p, new WebEvidenceMatcher(p));
        var provider = new DeepSeekWebEvidenceProvider(p, matcher, brave, "test-key");
        assertBraveResult(provider.fetch(DeepEvidenceSource.BILIBILI, restaurant()));
        assertThat(requests).hasSize(1);
    }

    private static Stream<RuntimeException> uncheckedFailures() {
        return Stream.of(new CancellationException("HTTP request cancelled"),
                new CompletionException(new HttpTimeoutException("HTTP request timed out")),
                new IllegalStateException("Unexpected search failure"));
    }

    @Test
    void readTimeoutFallsBackWithinOriginalDeadline() {
        delayMs = 2000;
        var p = properties();
        p.setReadTimeoutMs(100);
        long started = System.nanoTime();
        assertBraveResult(fetch(p));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1500);
        assertThat(requests).hasSize(1);
    }

    @Test
    void reservesFallbackTimeAndPassesOriginalDeadline() {
        delayMs = 2000;
        var p = properties();
        p.setReadTimeoutMs(3000);
        long started = System.nanoTime();
        long deadline = started + 1_000_000_000L;
        long[] fallbackDeadline = {0};
        var matcher = new WebEvidenceMatcher(p);
        var brave = new BraveWebEvidenceProvider(p, matcher);
        DeepEvidenceProvider fallback = new DeepEvidenceProvider() {
            @Override
            public DeepEvidenceBatch fetch(DeepEvidenceSource source, Restaurant restaurant) {
                throw new AssertionError("Fallback must receive original deadline");
            }

            @Override
            public DeepEvidenceBatch fetch(DeepEvidenceSource source, Restaurant restaurant, long deadlineNanos) {
                fallbackDeadline[0] = deadlineNanos;
                assertThat(System.nanoTime()).isLessThan(deadlineNanos);
                return brave.fetch(source, restaurant, deadlineNanos);
            }
        };
        var provider = new DeepSeekWebEvidenceProvider(p, matcher, fallback, "test-key");
        assertBraveResult(provider.fetch(DeepEvidenceSource.BILIBILI, restaurant(), deadline));
        assertThat(fallbackDeadline[0]).isEqualTo(deadline);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1500);
    }

    @Test
    void expiredDeadlineDoesNotCallEitherApi() {
        var result = provider(properties(), "test-key").fetch(DeepEvidenceSource.BILIBILI,
                restaurant(), System.nanoTime() - 1);
        assertThat(result.status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).isEmpty();
        assertThat(braveRequests).isEmpty();
    }

    @Test
    void failedBraveFallbackRemainsUnavailable() {
        responseStatus = 503;
        braveStatus = 503;
        assertThat(fetch(properties()).status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(braveRequests).hasSize(2);
    }

    @Test
    void defaultsPreserveCacheDeadlineAndMatcherThresholdAndVersionTracksFilters() {
        var p = new DeepEvidenceProperties();
        assertThat(p.isDeepseekEnabled()).isFalse();
        assertThat(p.getDeepseekBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(p.getDeepseekMinRerankScore()).isEqualTo(1.0);
        assertThat(p.getDeepseekMaxAgeDays()).isEqualTo(365);
        assertThat(p.getEvidenceCacheHours()).isEqualTo(12);
        assertThat(p.getOverallTimeoutMs()).isEqualTo(4000);
        assertThat(p.getEntityMatchThreshold()).isEqualTo(0.72);
        String oldVersion = p.getQueryVersion();
        p.setDeepseekEnabled(true);
        String enabledVersion = p.getQueryVersion();
        assertThat(enabledVersion).isNotEqualTo(oldVersion);
        p.setDeepseekMinRerankScore(2.0);
        assertThat(p.getQueryVersion()).isNotEqualTo(enabledVersion);
        String scoreVersion = p.getQueryVersion();
        p.setDeepseekMaxAgeDays(30);
        assertThat(p.getQueryVersion()).isNotEqualTo(scoreVersion);
    }

    @Test
    void springDefaultsToBraveAndSelectsDeepSeekOnlyWhenEnabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ProviderConfiguration.class);
        runner.run(context -> {
            assertThat(context).hasSingleBean(DeepEvidenceProvider.class);
            assertThat(context.getBean(DeepEvidenceProvider.class)).isInstanceOf(BraveWebEvidenceProvider.class);
        });
        runner.withPropertyValues("elma.deep-evidence.deepseek-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(DeepSeekWebEvidenceProvider.class);
            assertThat(context).hasSingleBean(BraveWebEvidenceProvider.class);
            assertThat(context.getBean(DeepEvidenceProvider.class)).isInstanceOf(DeepSeekWebEvidenceProvider.class);
            assertThat(context.getBean(DeepEvidenceProperties.class).isDeepseekEnabled()).isTrue();
        });
    }

    private DeepEvidenceProperties properties() {
        var p = new DeepEvidenceProperties();
        p.setEnabled(true);
        p.setDeepseekEnabled(true);
        p.setApiKey("test-brave-key");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        p.setBaseUrl(baseUrl);
        p.setDeepseekBaseUrl(baseUrl);
        p.setConnectTimeoutMs(500);
        p.setReadTimeoutMs(500);
        return p;
    }

    private DeepSeekWebEvidenceProvider provider(DeepEvidenceProperties p, String key) {
        var matcher = new WebEvidenceMatcher(p);
        return new DeepSeekWebEvidenceProvider(p, matcher, new BraveWebEvidenceProvider(p, matcher), key);
    }

    private DeepEvidenceBatch fetch(DeepEvidenceProperties p) {
        return provider(p, "test-deepseek-key").fetch(DeepEvidenceSource.BILIBILI, restaurant());
    }

    private Restaurant restaurant() {
        return TestRestaurants.full("a", "老王湘菜馆", 4.6, 500, 42);
    }

    private void assertBraveResult(DeepEvidenceBatch result) {
        assertThat(result.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(result.items()).extracting(WebEvidenceItem::url)
                .containsExactly("https://www.bilibili.com/video/brave");
        assertThat(braveRequests).hasSize(1);
    }

    private String item(String url, double score, String date) {
        return """
                {"rerank_score":%s,"title":"老王湘菜馆大学城店值得吃吗","url":"%s",
                "snippet":"麓山南路，分量足但高峰期排队","date":%s,"authority":2,
                "site_name":"哔哩哔哩","site_icon":"https://example.com/icon"}
                """.formatted(score, url, date == null ? "null" : "\"" + date + "\"");
    }

    private String results(String... items) {
        return "{\"results\":[" + String.join(",", items) + "]}";
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(new Request(exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        }
        send(exchange, responseStatus, responseBody);
    }

    private void respondBrave(HttpExchange exchange) throws IOException {
        braveRequests.add(exchange.getRequestURI().toString());
        send(exchange, braveStatus, """
                {"web":{"results":[{"title":"老王湘菜馆大学城店",
                "url":"https://www.bilibili.com/video/brave","description":"麓山南路"}]}}
                """);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Request(String method, String authorization, String contentType, String body) { }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeepEvidenceProperties.class)
    @Import({BraveWebEvidenceProvider.class, DeepSeekWebEvidenceProvider.class, WebEvidenceMatcher.class})
    static class ProviderConfiguration { }
}
