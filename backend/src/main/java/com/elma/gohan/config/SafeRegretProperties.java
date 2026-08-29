package com.elma.gohan.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SafeRegret v0.5 的 shadow、门禁和服务端灰度开关。 */
@ConfigurationProperties(prefix = "elma.safe-regret")
public class SafeRegretProperties {

    private static final int MAX_EXPERIMENT_KEY_LENGTH = 64;
    private boolean shadowEnabled = true;
    private boolean servingEnabled = false;
    private int rolloutPercentage = 0;
    private String experimentKey = "safe-regret-v0.5";
    private int featureSchemaVersion = 2;
    private double highRiskThreshold = 0.61;
    private double trustedSafeThreshold = 0.40;
    private double minimumDecisionConfidence = 0.75;

    public boolean isShadowEnabled() { return shadowEnabled; }
    public void setShadowEnabled(boolean value) { shadowEnabled = value; }
    public boolean isServingEnabled() { return servingEnabled; }
    public void setServingEnabled(boolean value) { servingEnabled = value; }
    public int getRolloutPercentage() { return rolloutPercentage; }
    public void setRolloutPercentage(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("rolloutPercentage 必须在 0~100 之间");
        }
        rolloutPercentage = value;
    }
    public String getExperimentKey() { return experimentKey; }
    public void setExperimentKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("experimentKey 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_EXPERIMENT_KEY_LENGTH) {
            throw new IllegalArgumentException("experimentKey 长度不能超过 64");
        }
        experimentKey = normalized;
    }
    public int getFeatureSchemaVersion() { return featureSchemaVersion; }
    public void setFeatureSchemaVersion(int value) {
        if (value < 1) throw new IllegalArgumentException("featureSchemaVersion 必须大于 0");
        featureSchemaVersion = value;
    }
    public double getHighRiskThreshold() { return highRiskThreshold; }
    public void setHighRiskThreshold(double value) {
        highRiskThreshold = unit(value, "highRiskThreshold");
    }
    public double getTrustedSafeThreshold() { return trustedSafeThreshold; }
    public void setTrustedSafeThreshold(double value) {
        trustedSafeThreshold = unit(value, "trustedSafeThreshold");
    }
    public double getMinimumDecisionConfidence() { return minimumDecisionConfidence; }
    public void setMinimumDecisionConfidence(double value) {
        minimumDecisionConfidence = unit(value, "minimumDecisionConfidence");
    }

    /** 防止错误环境变量制造同时“可信安全”又“高风险”的重叠区间。 */
    @PostConstruct
    public void validatePolicy() {
        if (experimentKey == null || experimentKey.isBlank()
                || experimentKey.length() > MAX_EXPERIMENT_KEY_LENGTH) {
            throw new IllegalArgumentException("experimentKey 必须为 1~64 个字符");
        }
        if (trustedSafeThreshold >= highRiskThreshold) {
            throw new IllegalArgumentException(
                    "trustedSafeThreshold 必须小于 highRiskThreshold");
        }
        if (servingEnabled) {
            throw new IllegalStateException(
                    "SafeRegret v0.5 当前仅接入 shadow，阶段 2 接线前禁止 serving-enabled");
        }
    }

    private static double unit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " 必须在 [0,1] 内");
        }
        return value;
    }
}
