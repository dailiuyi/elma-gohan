package com.elma.gohan.provider.poi.amap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.provider.poi.PoiProviderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AmapClientTransportTest {
    private final AmapProperties properties = new AmapProperties();
    private final SearchCondition condition = new SearchCondition(null, 500, null, null, "ANY", List.of());
    private AmapClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties.setKey("test-only-key");
        properties.setRateLimitPerSecond(1000);
        properties.setRetryInitialBackoffMs(0);
        properties.setRetryBackoffJitterMs(0);
        var builder = RestClient.builder().baseUrl("http://amap.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AmapClient(properties, new ObjectMapper());
        ReflectionTestUtils.setField(client, "restClient", builder.build());
    }

    static Stream<IOException> retryableFailures() {
        return Stream.of(new SocketTimeoutException("test timeout"), new ConnectException("test refused"),
                new SocketException("Connection RESET"), new IOException("wrapped", new SocketTimeoutException()));
    }

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void transientTransportFailureRetriesAndRecovers(IOException failure) {
        server.expect(anything()).andRespond(withException(failure));
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"1\",\"pois\":[]}", MediaType.APPLICATION_JSON));
        var result = client.searchAround(28.2, 112.9, condition);
        assertThat(result.retryCount()).isEqualTo(1);
        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }

    static Stream<IOException> permanentFailures() {
        return Stream.of(new IOException("unsupported transport"), new SocketException("not connected"),
                new SocketException());
    }

    @ParameterizedTest
    @MethodSource("permanentFailures")
    void permanentTransportFailureDoesNotRetry(IOException failure) {
        server.expect(anything()).andRespond(withException(failure));
        assertThatThrownBy(() -> client.searchAround(28.2, 112.9, condition))
                .isInstanceOf(PoiProviderException.class).hasMessage("高德周边搜索连接配置错误")
                .hasRootCause(failure);
        server.verify();
    }

    @Test
    void transportRetriesStopAtConfiguredLimit() {
        properties.setMaxRetries(1);
        server.expect(anything()).andRespond(withException(new SocketTimeoutException()));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> client.searchAround(28.2, 112.9, condition))
                .isInstanceOf(PoiProviderException.class).hasMessage("高德周边搜索暂时不可用");
        server.verify();
    }

    @Test
    void emptyHttpBodyRetriesAndRecovers() {
        server.expect(anything()).andRespond(withSuccess());
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"1\",\"pois\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.searchAround(28.2, 112.9, condition).retryCount()).isEqualTo(1);
        server.verify();
    }
}
