package com.elma.gohan.provider.poi.amap;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmapClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Map<String, String>> requests = new ArrayList<>();
    private final AtomicReference<Function<Integer, String>> responseForPage =
            new AtomicReference<>(page -> response(0, List.of()));
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
