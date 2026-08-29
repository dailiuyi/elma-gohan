package com.elma.gohan.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationDecisionSnapshotEntityTest {

    @Test
    void retainsReplayAndExperimentMetadata() {
        UUID id = UUID.randomUUID();
        UUID recommendationId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 30, 12, 0);
        String candidates = "{\"candidates\":[{\"sourcePoiId\":\"p1\",\"servedRank\":1}]}";

        var entity = new RecommendationDecisionSnapshotEntity(
                id,
                recommendationId,
                "safe-regret-v0.5",
                "SHADOW",
                "risk-v0.3.1",
                "risk-v0.5",
                "recommendation-v0.4.1",
                "recommendation-v0.5",
                42L,
                0.75,
                "sha256:config",
                1,
                candidates,
                createdAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getRecommendationLogId()).isEqualTo(recommendationId);
        assertThat(entity.getExperimentKey()).isEqualTo("safe-regret-v0.5");
        assertThat(entity.getVariant()).isEqualTo("SHADOW");
        assertThat(entity.getServedRiskAlgorithmVersion()).isEqualTo("risk-v0.3.1");
        assertThat(entity.getShadowRiskAlgorithmVersion()).isEqualTo("risk-v0.5");
        assertThat(entity.getServedRecommendationAlgorithmVersion())
                .isEqualTo("recommendation-v0.4.1");
        assertThat(entity.getShadowRecommendationAlgorithmVersion())
                .isEqualTo("recommendation-v0.5");
        assertThat(entity.getRandomSeed()).isEqualTo(42L);
        assertThat(entity.getSelectionPropensity()).isEqualTo(0.75);
        assertThat(entity.getConfigHash()).isEqualTo("sha256:config");
        assertThat(entity.getFeatureSchemaVersion()).isEqualTo(1);
        assertThat(entity.getAllCandidatesJson()).isEqualTo(candidates);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void migrationKeepsSnapshotOwnedByRecommendationLog() throws IOException {
        String migration;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V9__recommendation_decision_snapshot.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("REFERENCES recommendation_log (id) ON DELETE CASCADE")
                .contains("UNIQUE (recommendation_log_id, experiment_key)")
                .contains("all_candidates_json")
                .contains("selection_propensity")
                .contains("feature_schema_version")
                .contains("variant IN ('SHADOW', 'CONTROL', 'CANDIDATE')")
                .contains("jsonb_typeof(all_candidates_json) = 'object'")
                .contains("jsonb_typeof(all_candidates_json -> 'candidates') = 'array'");
    }
}
