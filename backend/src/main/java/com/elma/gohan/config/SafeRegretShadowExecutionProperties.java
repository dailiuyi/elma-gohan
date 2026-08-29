package com.elma.gohan.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SafeRegret shadow 的执行与关闭时间边界。 */
@ConfigurationProperties(prefix = "elma.safe-regret.shadow-execution")
public class SafeRegretShadowExecutionProperties {

    private int captureTimeoutSeconds = 3;
    private int transactionTimeoutSeconds = 2;
    private int shutdownTimeoutSeconds = 3;

    public int getCaptureTimeoutSeconds() {
        return captureTimeoutSeconds;
    }

    public void setCaptureTimeoutSeconds(int value) {
        captureTimeoutSeconds = positive(value, "captureTimeoutSeconds");
    }

    public int getTransactionTimeoutSeconds() {
        return transactionTimeoutSeconds;
    }

    public void setTransactionTimeoutSeconds(int value) {
        transactionTimeoutSeconds = positive(value, "transactionTimeoutSeconds");
    }

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public void setShutdownTimeoutSeconds(int value) {
        shutdownTimeoutSeconds = positive(value, "shutdownTimeoutSeconds");
    }

    @PostConstruct
    public void validate() {
        if (transactionTimeoutSeconds > captureTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "transactionTimeoutSeconds 不能大于 captureTimeoutSeconds");
        }
    }

    private static int positive(int value, String name) {
        if (value < 1 || value > 60) {
            throw new IllegalArgumentException(name + " 必须在 1~60 秒之间");
        }
        return value;
    }
}
