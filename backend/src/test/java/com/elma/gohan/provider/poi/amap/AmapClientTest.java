package com.elma.gohan.provider.poi.amap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class AmapClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Map<String, String>> requests = new ArrayList<>();
    private final AtomicReference<Function<Integer, String>> responseForPage =
            new AtomicReference<>(page -> response(0, List.of()));
    private final AtomicInteger requestNumber = new AtomicInteger();
    private HttpServer server;
    private AmapProperties props;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v3/place/around", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            synchronized (requests) {
                requests.add(query);
            }
            int page = Integer.parseInt(query.getOrDefault("page", "1"));
            requestNumber.incrementAndGet();
            byte[] body = responseForPage.get().apply(page).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        props = new AmapProperties();
        props.setKey("test-key");
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setPageSize(25);
        props.setMaxPages(8);
        props.setTargetCandidates(10);
        props.setRateLimitPerSecond(1000);
        props.setRetryInitialBackoffMs(0);
        props.setRetryBackoffJitterMs(0);
        props.setSearchTypesByCategory(Map.of(
                "ANY", "050000",
                "HOT_POT", "050117",
                "DESSERT_DRINK", "050500|050600|050700|050800|050900",
                "BARBECUE", "050100"));
        props.setSearchKeywordsByCategory(Map.of("BARBECUE", "烧烤"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("前 50 家低于环带下限时继续翻到第三页召回有效候选")
    void continuesUntilDistanceBandHasCandidates() {
        List<String> firstPage = pois("near-a-", 25, 100, 20);
        List<String> secondPage = pois("near-b-", 25, 700, 20);
        List<String> thirdPage = pois("band-", 10, 2100, 10);
        responseForPage.set(page -> switch (page) {
            case 1 -> response(60, firstPage);
            case 2 -> response(60, secondPage);
            case 3 -> response(60, thirdPage);
            default -> response(60, List.of());
        });
        AmapClient client = new AmapClient(props, objectMapper);

        AmapSearchResult result = client.searchAround(28.2, 112.9,
                new SearchCondition(2000, 3000, null, null, "ANY", List.of()));

        assertThat(result.pagesFetched()).isEqualTo(3);
        assertThat(result.fetchedCount()).isEqualTo(60);
        assertThat(result.pois()).hasSize(60);
        assertThat(result.lastFetchedDistanceMeters()).isEqualTo(2190);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("用户品类转换为更窄的高德 types，必要时携带关键词")
    void sendsCategoryAwareTypesAndKeyword() {
        AmapClient client = new AmapClient(props, objectMapper);

        client.searchAround(28.2, 112.9,
                new SearchCondition(null, 1000, null, null, "DESSERT_DRINK", List.of()));
        client.searchAround(28.2, 112.9,
                new SearchCondition(null, 1000, null, null, "BARBECUE", List.of()));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0))
                .containsEntry("types", "050500|050600|050700|050800|050900")
                .containsEntry("sortrule", "distance")
                .doesNotContainKey("keywords");
        assertThat(requests.get(1))
                .containsEntry("types", "050100")
                .containsEntry("keywords", "烧烤");
    }

    @Test
    @DisplayName("跨页 POI 去重并在达到分页上限时标记未搜完整")
    void deduplicatesAcrossPagesAndMarksTruncation() {
        props.setPageSize(2);
        props.setMaxPages(2);
        props.setTargetCandidates(50);
        responseForPage.set(page -> page == 1
                ? response(6, List.of(poi("A", 100), poi("B", 200)))
                : response(6, List.of(poi("B", 200), poi("C", 300))));
        AmapClient client = new AmapClient(props, objectMapper);

        AmapSearchResult result = client.searchAround(28.2, 112.9,
                new SearchCondition(null, 1000, null, null, "ANY", List.of()));

        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.fetchedCount()).isEqualTo(4);
        assertThat(result.pois()).extracting(p -> p.path("id").asText())
                .containsExactly("A", "B", "C");
        assertThat(result.truncated()).isTrue();
        assertThat(result.providerTotalCount()).isEqualTo(6);
        assertThat(result.completionReason().name()).isEqualTo("MAX_PAGES_REACHED");
    }

    @Test
    @DisplayName("10021 最多重试两次并能在第二次恢复")
    void retriesQpsFailureAndRecovers() {
        responseForPage.set(page -> requestNumber.get() == 1
                ? failure("CUQPS_HAS_EXCEEDED_THE_LIMIT", "10021")
                : response(1, List.of(poi("A", 100))));
        AmapClient client = new AmapClient(props, objectMapper);

        AmapSearchResult result = client.searchAround(28.2, 112.9,
                new SearchCondition(null, 500, null, null, "ANY", List.of()));

        assertThat(requests).hasSize(2);
        assertThat(result.retryCount()).isEqualTo(1);
        assertThat(result.pagesFetched()).isEqualTo(1);
    }

    @Test
    @DisplayName("可重试错误连续失败时总尝试次数恰好为三")
    void stopsAfterThreeRetryableAttempts() {
        responseForPage.set(page -> failure("CUQPS_HAS_EXCEEDED_THE_LIMIT", "10021"));
        AmapClient client = new AmapClient(props, objectMapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.searchAround(28.2, 112.9,
                        new SearchCondition(null, 500, null, null, "ANY", List.of())))
                .isInstanceOf(com.elma.gohan.provider.poi.PoiProviderException.class);

        assertThat(requests).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10001", "10003", "20001"})
    @DisplayName("Key、日额度和参数等确定性错误不重试")
    void doesNotRetryDeterministicFailure(String infocode) {
        responseForPage.set(page -> failure("DETERMINISTIC_FAILURE", infocode));
        AmapClient client = new AmapClient(props, objectMapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.searchAround(28.2, 112.9,
                        new SearchCondition(null, 500, null, null, "ANY", List.of())))
                .isInstanceOf(com.elma.gohan.provider.poi.PoiProviderException.class);

        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("已有成功页后重试耗尽时保留部分召回并标记未搜完整")
    void keepsSuccessfulPagesWhenLaterPageExhaustsRetries() {
        props.setTargetCandidates(100);
        responseForPage.set(page -> page == 1
                ? response(100, pois("page-1-", 25, 100, 10))
                : failure("CUQPS_HAS_EXCEEDED_THE_LIMIT", "10021"));
        AmapClient client = new AmapClient(props, objectMapper);

        AmapSearchResult result = client.searchAround(28.2, 112.9,
                new SearchCondition(null, 3000, null, null, "ANY", List.of()));

        assertThat(requests).hasSize(4);
        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.pois()).hasSize(25);
        assertThat(result.retryCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.completionReason())
                .isEqualTo(com.elma.gohan.provider.poi.PoiSearchCompletionReason
                        .UPSTREAM_RETRY_EXHAUSTED);
    }

    @Test
    @DisplayName("召回总期限到达后保留已成功页并标记未搜完整")
    void marksPartialResultWhenDeadlineIsReached() {
        props.setRateLimitPerSecond(3);
        props.setRecallDeadlineMs(100);
        props.setTargetCandidates(100);
        responseForPage.set(page -> response(100, pois("page-" + page + "-", 25, 100, 1)));
        AmapClient client = new AmapClient(props, objectMapper);

        AmapSearchResult result = client.searchAround(28.2, 112.9,
                new SearchCondition(null, 3000, null, null, "ANY", List.of()));

        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.completionReason().name()).isEqualTo("RECALL_DEADLINE_REACHED");
    }

    @Test
    @DisplayName("诊断日志保留 infocode 但不泄露 Key、URL 或精确坐标")
    void diagnosticLogDoesNotLeakSensitiveRequestData() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(AmapClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        responseForPage.set(page -> failure("INVALID_USER_KEY", "10001"));
        try {
            AmapClient client = new AmapClient(props, objectMapper);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.searchAround(
                            28.212345, 112.912345,
                            new SearchCondition(null, 500, null, null, "ANY", List.of())))
                    .isInstanceOf(com.elma.gohan.provider.poi.PoiProviderException.class);

            String messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(messages).contains("infocode=10001");
            assertThat(messages).doesNotContain("test-key")
                    .doesNotContain("/v3/place/around")
                    .doesNotContain("28.212345")
                    .doesNotContain("112.912345");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static List<String> pois(String prefix, int count, int firstDistance, int step) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(poi(prefix + i, firstDistance + i * step));
        }
        return result;
    }

    private static String poi(String id, int distance) {
        return "{\"id\":\"" + id + "\",\"name\":\"店" + id
                + "\",\"type\":\"餐饮服务;中餐厅\",\"typecode\":\"050100\","
                + "\"location\":\"112.9,28.2\",\"distance\":\"" + distance + "\"}";
    }

    private static String response(int count, List<String> pois) {
        return "{\"status\":\"1\",\"count\":\"" + count + "\",\"pois\":["
                + String.join(",", pois) + "]}";
    }

    private static String failure(String info, String infocode) {
        return "{\"status\":\"0\",\"info\":\"" + info
                + "\",\"infocode\":\"" + infocode + "\"}";
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? ""
                            : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
