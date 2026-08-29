package com.elma.gohan.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SafeRegretPropertiesTest {

    @Test
    void defaultsUseRecalibratedCredibleIntervalConfidenceGateAndSchema() {
        SafeRegretProperties properties = new SafeRegretProperties();

        assertThat(properties.getMinimumDecisionConfidence()).isEqualTo(0.75);
        assertThat(properties.getFeatureSchemaVersion()).isEqualTo(2);
    }

    @Test
    void rejectsOverlappingSafeAndHighRiskThresholds() {
        SafeRegretProperties properties = new SafeRegretProperties();
        properties.setTrustedSafeThreshold(0.70);
        properties.setHighRiskThreshold(0.60);

        assertThatThrownBy(properties::validatePolicy)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须小于");
    }

    @Test
    void rejectsServingSwitchUntilPhaseTwoIsActuallyWired() {
        SafeRegretProperties properties = new SafeRegretProperties();
        properties.setServingEnabled(true);

        assertThatThrownBy(properties::validatePolicy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅接入 shadow");
    }

    @Test
    void rejectsExperimentKeyLongerThanDatabaseColumn() {
        SafeRegretProperties properties = new SafeRegretProperties();

        assertThatThrownBy(() -> properties.setExperimentKey("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }
}
