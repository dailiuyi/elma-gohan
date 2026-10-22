package com.elma.gohan.provider.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BaiduPlaceApiProviderTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private String responseBody;
    private int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        responseBody = successBody();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void suggestionFiltersInvalidIdentityAndCoordinatesAndBuildsAddresses() {
        responseBody = """
                {"status":0,"result":[
                  {"name":"missing uid","location":{"lat":28,"lng":112}},
                  {"uid":"missing-name","location":{"lat":28,"lng":112}},
                  {"uid":"missing-location","name":"店"},
                  {"uid":"bad-lat","name":"店","location":{"lat":"bad","lng":112}},
                  {"uid":"bad-lng","name":"店","location":{"lat":28,"lng":"bad"}},
                  {"uid":"a","name":"湘菜甲","address":"完整地址","location":{"lat":28,"lng":112}},
                  {"uid":"b","name":"湘菜乙","district":"岳麓区","business":"大学城","location":{"lat":28,"lng":112}},
                  {"uid":"c","name":"湘菜丙","district":"岳麓区","location":{"lat":28,"lng":112}},
                  {"uid":"d","name":"湘菜丁","business":"大学城","location":{"lat":28,"lng":112}},
                  {"uid":"e","name":"湘菜戊","location":{"lat":28,"lng":112}}]}
                """;

        PlatformSearchResult result = provider(properties()).searchSuggestion(null, " ", null);

        assertThat(result.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.evidence()).extracting(PlatformEvidence::address)
                .containsExactly("完整地址", "岳麓区大学城", "岳麓区", "大学城", null);
        assertThat(result.evidence()).allSatisfy(e -> {
            assertThat(e.latitude()).isEqualTo(28.0);
            assertThat(e.longitude()).isEqualTo(112.0);
            assertThat(e.overallRating()).isNull();
        });
        assertThat(URLDecoder.decode(requests.get(0), StandardCharsets.UTF_8))
                .contains("region=长沙").doesNotContain("location=");
    }

    @ParameterizedTest
    @CsvSource({"401,UPSTREAM_RATE_LIMIT", "302,DAILY_QUOTA", "2,UPSTREAM_ERROR"})
    void businessErrorsHaveConsistentReasonsAcrossEndpoints(int status, String reason) {
        responseBody = "{\"status\":" + status + "}";
        BaiduPlaceApiProvider provider = provider(properties());

        assertThat(provider.searchV3(new Location(28.2, 112.9), 500, 0).failureReason())
                .isEqualTo(reason);
        assertThat(provider.searchRegion(null, "").failureReason()).isEqualTo(reason);
        assertThat(provider.searchSuggestion(null, null, "").failureReason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":0}", "{\"status\":0,\"results\":[],\"result\":[]}"})
    void missingOrEmptyResultArraysReturnNoData(String body) {
        responseBody = body;
        BaiduPlaceApiProvider provider = provider(properties());

        assertThat(provider.searchV3(new Location(28.2, 112.9), 500, 0).status())
                .isEqualTo(EvidenceStatus.NO_DATA);
        assertThat(provider.searchRegion(null, null).status()).isEqualTo(EvidenceStatus.NO_DATA);
        assertThat(provider.searchSuggestion(null, null, null).status()).isEqualTo(EvidenceStatus.NO_DATA);
    }

    @Test
    void invalidPlaceRowsAreSkippedAndMalformedNumbersRemainUnknown() {
        responseBody = """
                {"status":0,"total":"unknown","results":[
                  {"uid":"missing-name"},{"name":"missing-uid"},
                  {"uid":"valid","name":"湘菜","location":{"lat":"bad","lng":"-"},
                   "detail_info":{"overall_rating":"bad","price":" ","comment_num":"12.6条"}}]}
                """;
        BaiduProperties properties = properties();
        properties.setPageSize(100);

        PlatformSearchResult result = provider(properties).searchV3(new Location(28.2, 112.9),
                500, -1, "湘菜");

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.total()).isNull();
        assertThat(result.pageNumber()).isZero();
        assertThat(result.pageSize()).isEqualTo(20);
        PlatformEvidence evidence = result.evidence().get(0);
        assertThat(evidence.latitude()).isNull();
        assertThat(evidence.longitude()).isNull();
        assertThat(evidence.overallRating()).isNull();
        assertThat(evidence.averagePrice()).isNull();
        assertThat(evidence.commentCount()).isEqualTo(13);
    }

    @Test
    void regionClampsPageSizeAndUsesDefaultQueryAndRegion() {
        BaiduProperties properties = properties();
        properties.setPageSize(1);

        PlatformSearchResult result = provider(properties).searchRegion(" ", " ");

        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(URLDecoder.decode(requests.get(0), StandardCharsets.UTF_8))
                .contains("query=" + properties.getQuery()).contains("region=长沙")
                .contains("page_size=10").contains("city_limit=true");
    }

    @Test
    void disabledProviderDoesNotSendRequestsForAnyEndpoint() {
        BaiduProperties properties = properties();
        properties.setEnabled(false);
        BaiduPlaceApiProvider provider = provider(properties);

        assertThat(provider.searchV3(new Location(28.2, 112.9), 500, 0).status())
                .isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(provider.searchRegion(null, null).status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(provider.searchSuggestion(null, null, null).status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).isEmpty();
    }

    @Test
    void exhaustedRequestBudgetAlsoPreventsRegionAndSuggestionRequests() throws InterruptedException {
        BaiduPlaceApiProvider provider = provider(properties());

        try (var ignored = new BaiduCallContext(1)) {
            Thread.sleep(5);
            assertThat(provider.searchRegion(null, null).failureReason()).isEqualTo("LOCAL_QUEUE_TIMEOUT");
            assertThat(provider.searchSuggestion(null, null, null).failureReason()).isEqualTo("LOCAL_QUEUE_TIMEOUT");
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void httpFailureDegradesEveryEndpointWithoutThrowing() {
        responseStatus = 503;
        BaiduPlaceApiProvider provider = provider(properties());

        assertThat(provider.searchV3(new Location(28.2, 112.9), 500, 0).status())
                .isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(provider.searchRegion(null, null).status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(provider.searchSuggestion(null, null, null).status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).hasSize(3);
    }

    @Test
    void mapsStringNumbersAndUsesGcj02BatchParameters() {
        BaiduPlaceApiProvider provider = provider(properties());

        PlatformSearchResult v3 = provider.searchV3(new Location(28.2291, 112.9412), 1200, 1);
        PlatformSearchResult v2 = provider.searchV2(new Location(28.2291, 112.9412), 1200);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).startsWith("/place/v3/around?");
        String query = URLDecoder.decode(requests.get(0), StandardCharsets.UTF_8);
        assertThat(query).contains("location=28.2291,112.9412")
                .contains("coord_type=2")
                .contains("ret_coordtype=gcj02ll")
                .contains("scope=2")
                .contains("radius_limit=true")
                .contains("page_size=20")
                .contains("page_num=1");
        assertThat(v3.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(v3.total()).isEqualTo(2);
        assertThat(v3.pageNumber()).isEqualTo(1);
        assertThat(v3.pageSize()).isEqualTo(20);
        assertThat(v2.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        PlatformEvidence evidence = v3.evidence().get(0);
        assertThat(evidence.overallRating()).isEqualTo(4.2);
        assertThat(evidence.tasteRating()).isEqualTo(4.0);
        assertThat(evidence.serviceRating()).isNull();
        assertThat(evidence.environmentRating()).isNull();
        assertThat(evidence.commentCount()).isEqualTo(2600);
        assertThat(evidence.averagePrice()).isEqualTo(62);
    }

    @Test
    void businessErrorAndMissingAkDegradeWithoutThrowing() {
        responseBody = "{\"status\":2,\"message\":\"invalid request\"}";
        BaiduPlaceApiProvider provider = provider(properties());

        assertThat(provider.searchV3(new Location(28.2, 112.9), 1000, 0).status())
                .isEqualTo(EvidenceStatus.UNAVAILABLE);

        BaiduProperties disabled = properties();
        disabled.setAk("");
        assertThat(provider(disabled)
                .searchV3(new Location(28.2, 112.9), 1000, 0).status())
                .isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"401,UPSTREAM_RATE_LIMIT", "402,UPSTREAM_RATE_LIMIT", "301,PERMANENT_QUOTA",
            "302,DAILY_QUOTA", "240,CONFIGURATION_ERROR"})
    void classifiesBusinessFailuresOnEveryEndpoint(int status, String reason) {
        responseBody = "{\"status\":" + status + "}";
        BaiduPlaceApiProvider provider = provider(properties());
        Location center = new Location(28.2291, 112.9412);

        assertThat(List.of(provider.searchV3(center, 500, 0),
                provider.searchNearby(center, 500, "餐饮"),
                provider.searchRegion("餐饮", "长沙"),
                provider.searchSuggestion(center, "餐饮", "长沙"),
                provider.searchV2(center, 500)))
                .allSatisfy(result -> assertThat(result.failureReason()).isEqualTo(reason));
    }

    @ParameterizedTest
    @CsvSource({"429,UPSTREAM_RATE_LIMIT", "503,UPSTREAM_UNAVAILABLE", "403,CONFIGURATION_ERROR"})
    void distinguishesHttpStatusFromBaiduBusinessCode(int status, String reason) {
        responseStatus = status;
        assertThat(provider(properties()).searchV3(new Location(28.2291,112.9412), 500, 0).failureReason())
                .isEqualTo(reason);
    }

    @Test
    void nearbyNameRecallUsesStrictRadiusAndDistanceOrder() {
        BaiduPlaceApiProvider provider = provider(properties());

        provider.searchNearby(new Location(28.2291, 112.9412), 500,
                "湘味小馆麓谷店");

        assertThat(requests).hasSize(1);
        String query = URLDecoder.decode(requests.get(0), StandardCharsets.UTF_8);
        assertThat(query).contains("/place/v3/around?")
                .contains("query=湘味小馆麓谷店")
                .contains("radius=500")
                .contains("radius_limit=true")
                .contains("filter=industry_type:cater|sort_name:distance|sort_rule:1")
                .doesNotContain("radius_limit=false");
    }

    @Test
    void supportsEveryPlaceEndpoint() {
        BaiduPlaceApiProvider provider = provider(properties());
        Location center = new Location(28.2291, 112.9412);

        provider.searchV3(center, 1000, 0);
        provider.searchNearby(center, 500, "湘味小馆");
        provider.searchRegion("湘味小馆", "长沙");
        provider.searchSuggestion(center, "湘味小馆", "长沙");
        provider.searchV2(center, 1000);

        assertThat(requests).hasSize(5);
    }

    @Test
    void expiredDeadlineDoesNotCallBaidu() throws InterruptedException {
        BaiduPlaceApiProvider provider = provider(properties());
        try (var ignored = new BaiduCallContext(1)) {
            Thread.sleep(5);
            assertThat(provider.searchV3(new Location(28.2291, 112.9412), 1000, 0).status())
                    .isEqualTo(EvidenceStatus.UNAVAILABLE);
        }
        assertThat(requests).isEmpty();
    }

    private BaiduPlaceApiProvider provider(BaiduProperties properties) {
        return new BaiduPlaceApiProvider(properties);
    }

    private BaiduProperties properties() {
        BaiduProperties properties = new BaiduProperties();
        properties.setAk("test-ak-not-a-secret");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutMs(500);
        return properties;
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String successBody() {
        return """
                {"status":0,"total":2,"results":[{
                  "uid":"baidu-1","name":"湘味小馆","address":"麓山南路1号",
                  "location":{"lat":"28.2291","lng":"112.9412"},
                  "telephone":"0731-12345678",
                  "detail_info":{"overall_rating":"4.2","taste_rating":"4.0",
                    "comment_num":"2600条","price":"62元","shop_hours":"09:00-21:00"}
                }]}
                """;
    }
}
