package com.elma.gohan.provider.deep;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BraveWebEvidenceProviderTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private volatile boolean fallbackScenario;
    private volatile int responseStatus = 200;
    private volatile long delayMs;
    private volatile String customBody;
    private final AtomicReference<String> postBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @Test
    void preservesProductionBaiduAiTransportAndUsesSameStoreContext() {
        var p = properties();
        p.setBaiduAiSearchEnabled(true);
        p.setBaiduAiBaseUrl(p.getBaseUrl());
        customBody = """
                {"references":[{"title":"老王湘菜馆大学城店", "url":"https://www.bilibili.com/video/BV123",
                "content":"麓山南路，分量足但高峰期排队", "date":"2026-08-10"}]}
                """;
        var result = new BraveWebEvidenceProvider(p, new WebEvidenceMatcher(p)).fetch(
                DeepEvidenceSource.BILIBILI, TestRestaurants.full("a", "老王湘菜馆", 4.6, 500, 42));
        assertThat(result.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(result.items().get(0).snippet()).contains("排队");
        assertThat(requests).containsExactly("/v2/ai_search/web_search");
        assertThat(authorization.get()).isEqualTo("Bearer test-brave-key");
        assertThat(postBody.get()).contains("baidu_search_v2", "turbo", "bilibili.com", "长沙", "麓山南路")
                .doesNotContain("search_recency_filter", "site:", "extra_snippets");
        assertThat(p.getQueryVersion()).startsWith("baidu-ai-query-v0.3");
    }

    @Test
    void baiduBusinessFailureIsUnavailable() {
        var p = properties();
        p.setBaiduAiSearchEnabled(true);
        p.setBaiduAiBaseUrl(p.getBaseUrl());
        customBody = "{\"code\":429,\"message\":\"limited\"}";
        assertThat(new BraveWebEvidenceProvider(p, new WebEvidenceMatcher(p)).fetch(
                DeepEvidenceSource.DIANPING, TestRestaurants.full("a", 4.5, 100)).status())
                .isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).hasSize(2);
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsOnlyWhitelistedRelevantResultsAndSendsFixedParameters() {
        DeepEvidenceProperties properties = properties();
        BraveWebEvidenceProvider provider = new BraveWebEvidenceProvider(properties,
                new WebEvidenceMatcher(properties));

        DeepEvidenceBatch result = provider.fetch(DeepEvidenceSource.BILIBILI,
                TestRestaurants.full("a", "老王湘菜馆", 4.6, 500, 42));

        assertThat(result.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).url())
                .isEqualTo("https://www.bilibili.com/video/BV123");
        assertThat(result.items().get(0).publishedAt().toString())
                .isEqualTo("2026-08-10T09:30:00Z");
        String decoded = URLDecoder.decode(requests.get(0), StandardCharsets.UTF_8);
        assertThat(decoded).contains("/res/v1/web/search?")
                .contains("site:bilibili.com/video")
                .contains("麓山南路")
                .doesNotContain("麓山南路 1 号")
                .doesNotContain("freshness=pm")
                .contains("country=CN")
                .contains("search_lang=zh-hans")
                .contains("spellcheck=false")
                .contains("extra_snippets=true");
        assertThat(token.get()).isEqualTo("test-brave-key");
        assertThat(accept.get()).isEqualTo("application/json");
    }

    @Test
    void retriesBrandAndLocationWhenFullNameDoesNotMatch() {
        fallbackScenario = true;
        DeepEvidenceProperties properties = properties();
        BraveWebEvidenceProvider provider = new BraveWebEvidenceProvider(properties,
                new WebEvidenceMatcher(properties));

        DeepEvidenceBatch result = provider.fetch(DeepEvidenceSource.BILIBILI,
                TestRestaurants.full("a", "老王湘菜馆（大学城店）", 4.6, 500, 42));

        assertThat(result.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(result.items()).hasSize(1);
        assertThat(requests).hasSize(2);
        assertThat(URLDecoder.decode(requests.get(0), StandardCharsets.UTF_8))
                .contains("大学城").doesNotContain("freshness=pm");
        assertThat(URLDecoder.decode(requests.get(1), StandardCharsets.UTF_8))
                .contains("大学城").doesNotContain("freshness=pm");
    }

    @Test
    void missingKeyDegradesWithoutRequest() {
        DeepEvidenceProperties properties = properties();
        properties.setApiKey("");
        BraveWebEvidenceProvider provider = new BraveWebEvidenceProvider(properties,
                new WebEvidenceMatcher(properties));

        assertThat(provider.fetch(DeepEvidenceSource.DIANPING,
                TestRestaurants.full("a", 4.5, 100)).status())
                .isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).isEmpty();
    }

    @Test
    void failedFallbackIsUnavailableInsteadOfEmpty() {
        responseStatus = 503;
        var p = properties();
        var provider = new BraveWebEvidenceProvider(p, new WebEvidenceMatcher(p));
        assertThat(provider.fetch(DeepEvidenceSource.BILIBILI, TestRestaurants.full("a", "老王湘菜馆", 4.6, 500, 42)).status())
                .isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).hasSize(2);
    }

    @Test
    void extraSnippetsCanConfirmTheBranch() {
        customBody = """
                {"web":{"results":[{"title":"老王湘菜馆探店","url":"https://www.bilibili.com/video/BV123",
                "description":"排队","extra_snippets":["长沙大学城店，麓山南路"]}]}}
                """;
        var p = properties();
        var result = new BraveWebEvidenceProvider(p, new WebEvidenceMatcher(p)).fetch(DeepEvidenceSource.BILIBILI,
                TestRestaurants.full("a", "老王湘菜馆(大学城店)", 4.6, 500, 42));
        assertThat(result.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(result.items().get(0).snippet()).contains("长沙大学城店");
    }

    @Test
    void deadlineDoesNotStartAnotherAttempt() {
        var p = properties();
        var provider = new BraveWebEvidenceProvider(p, new WebEvidenceMatcher(p));
        assertThat(provider.fetch(DeepEvidenceSource.BILIBILI, TestRestaurants.full("a", 4.5, 10),
                System.nanoTime() - 1).status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).isEmpty();
    }

    private DeepEvidenceProperties properties() {
        DeepEvidenceProperties properties = new DeepEvidenceProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-brave-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutMs(500);
        return properties;
    }

    private void respond(HttpExchange exchange) throws IOException {
        String request = exchange.getRequestURI().toString();
        try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        requests.add(request);
        postBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        token.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
        accept.set(exchange.getRequestHeaders().getFirst("Accept"));
        byte[] body = fallbackScenario && requests.size() == 1
                ? """
                  {"web":{"results":[
                    {"title":"另一家店","url":"https://www.bilibili.com/video/BV999","description":"完全无关"}
                  ]}}
                  """.getBytes(StandardCharsets.UTF_8)
                : """
                {"web":{"results":[
                  {"title":"老王湘菜馆大学城店值得吃吗","url":"https://www.bilibili.com/video/BV123?utm_source=test","description":"麓山南路，分量足但高峰期排队","page_age":"2026-08-10T09:30:00"},
                  {"title":"老王湘菜馆错误域名","url":"https://example.com/video/1","description":"无关"},
                  {"title":"另一家店","url":"https://www.bilibili.com/video/BV999","description":"完全无关"}
                ]}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        if (customBody != null) body = customBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
