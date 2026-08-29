package com.elma.gohan.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SafeRegretShadowExecutionPropertiesTest {

    @Test
    void rejectsInvalidOrInvertedTimeouts() {
        SafeRegretShadowExecutionProperties properties =
                new SafeRegretShadowExecutionProperties();

        assertThatThrownBy(() -> properties.setCaptureTimeoutSeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setShutdownTimeoutSeconds(61))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setCaptureTimeoutSeconds(1);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionTimeoutSeconds");
    }
}
