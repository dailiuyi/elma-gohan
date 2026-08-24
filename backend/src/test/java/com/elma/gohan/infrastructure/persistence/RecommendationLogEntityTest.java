package com.elma.gohan.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationLogEntityTest {

    @Test
    void retainsReplaySeedAndSelectionSnapshot() {
        UUID logId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        String snapshot = "[{\"source\":\"AMAP\",\"sourcePoiId\":\"p1\","
                + "\"diversityKey\":\"MEAL\",\"lowRegretScore\":88.5}]";

        RecommendationLogEntity entity = new RecommendationLogEntity(
                logId, userId, "{}", 1, restaurantId, restaurantId,
                10, 88.5, "risk-v0.3", "recommendation-v0.3", 42L,
                snapshot, LocalDateTime.of(2026, 8, 22, 12, 0));

        assertThat(entity.getRandomSeed()).isEqualTo(42L);
        assertThat(entity.getSelectionSnapshotJson()).isEqualTo(snapshot);
    }
}
