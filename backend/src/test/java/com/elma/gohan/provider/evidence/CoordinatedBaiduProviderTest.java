package com.elma.gohan.provider.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class CoordinatedBaiduProviderTest {
    private final BaiduDatabase database = mock(BaiduDatabase.class);
    private final BaiduPlaceApiProvider delegate = mock(BaiduPlaceApiProvider.class);
    private final BaiduProperties properties = new BaiduProperties();
    private final Location center = new Location(28.2, 112.9);

    @Test
    void disabledProviderRejectsAllEntryPointsBeforeDatabaseOrHttp() {
        properties.setEnabled(false);
        var provider = new CoordinatedBaiduProvider(database, delegate, new ObjectMapper(), properties, new SimpleMeterRegistry());
        assertThat(provider.searchV3(center, 500, 0).failureReason()).isEqualTo("DISABLED");
        assertThat(provider.searchNearby(center, 500, "餐厅").failureReason()).isEqualTo("DISABLED");
        assertThat(provider.searchRegion("餐厅", "长沙").failureReason()).isEqualTo("DISABLED");
        assertThat(provider.searchSuggestion(center, "餐厅", null).failureReason()).isEqualTo("DISABLED");
        assertThat(provider.searchV2(center, 500).failureReason()).isEqualTo("DISABLED");
        verifyNoInteractions(database, delegate);
    }

    @Test
    void missingKeyDoesNotAcquireConnection() {
        properties.setEnabled(true);
        properties.setAk(" ");
        var provider = new CoordinatedBaiduProvider(database, delegate, new ObjectMapper(), properties, new SimpleMeterRegistry());
        assertThat(provider.searchNearby(center, 500, "餐厅").failureReason()).isEqualTo("DISABLED");
        verifyNoInteractions(database, delegate);
    }

    @Test
    void serializationFailureDoesNotAcquireConnectionOrCallDelegate() throws Exception {
        properties.setEnabled(true);
        properties.setAk("test-only-key");
        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("test serialization failure") { });
        var provider = new CoordinatedBaiduProvider(database, delegate, mapper, properties, new SimpleMeterRegistry());
        assertThatThrownBy(() -> provider.searchNearby(center, 500, "餐厅"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Cannot serialize Baidu query");
        verifyNoInteractions(database, delegate);
    }

    @Test
    void databaseFailureReturnsUnavailableAndRestoresOuterCallContext() throws Exception {
        properties.setEnabled(true);
        properties.setAk("test-only-key");
        when(database.open()).thenThrow(new SQLException("test database unavailable"));
        var provider = new CoordinatedBaiduProvider(database, delegate, new ObjectMapper(), properties, new SimpleMeterRegistry());
        try (var ignored = new BaiduCallContext(1000)) {
            long before = BaiduCallContext.remainingMillis();
            var result = provider.searchNearby(center, 500, "餐厅");
            assertThat(result.status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
            assertThat(result.failureReason()).isEqualTo("COORDINATION_UNAVAILABLE");
            assertThat(BaiduCallContext.remainingMillis()).isLessThanOrEqualTo(before);
        }
        assertThat(BaiduCallContext.remainingMillis()).isEqualTo(8000);
        verifyNoInteractions(delegate);
    }
}
