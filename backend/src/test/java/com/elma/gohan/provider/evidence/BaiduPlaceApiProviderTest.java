package com.elma.gohan.provider.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class BaiduPlaceApiProviderTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private String responseBody;

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
    void everyPlaceEndpointSharesTheSameGlobalRateLimiter() {
        BaiduProperties properties = properties();
        BaiduPlaceRateLimiter limiter = mock(BaiduPlaceRateLimiter.class);
        when(limiter.acquire()).thenReturn(new BaiduPlaceRateLimiter.Permit(true, 0));
        BaiduPlaceApiProvider provider = new BaiduPlaceApiProvider(properties, limiter);
        Location center = new Location(28.2291, 112.9412);

        provider.searchV3(center, 1000, 0);
        provider.searchNearby(center, 500, "湘味小馆");
        provider.searchRegion("湘味小馆", "长沙");
        provider.searchSuggestion(center, "湘味小馆", "长沙");
        provider.searchV2(center, 1000);

        verify(limiter, times(5)).acquire();
        assertThat(requests).hasSize(5);
    }

    @Test
    void rejectedGlobalPermitDoesNotCallBaiduAndReturnsUnavailable() {
        BaiduPlaceRateLimiter limiter = mock(BaiduPlaceRateLimiter.class);
        when(limiter.acquire()).thenReturn(new BaiduPlaceRateLimiter.Permit(false, 1000));
        BaiduPlaceApiProvider provider = new BaiduPlaceApiProvider(properties(), limiter);

        PlatformSearchResult result = provider.searchV3(
                new Location(28.2291, 112.9412), 1000, 0);

        assertThat(result.status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(requests).isEmpty();
    }

    private BaiduPlaceApiProvider provider(BaiduProperties properties) {
        return new BaiduPlaceApiProvider(properties, new BaiduPlaceRateLimiter(properties));
    }

    private BaiduProperties properties() {
        BaiduProperties properties = new BaiduProperties();
        properties.setAk("test-ak-not-a-secret");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutMs(500);
        properties.setRateLimitPerSecond(1000);
        properties.setRateLimitMaxWaitMs(100);
        return properties;
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
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
