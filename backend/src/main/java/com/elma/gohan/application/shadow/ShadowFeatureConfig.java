package com.elma.gohan.application.shadow;

/** Shadow 特征桥接层的显式、可哈希默认值。 */
public record ShadowFeatureConfig(
        double qualityPriorMean,
        double qualityPriorStrength,
        double categoryTasteShare,
        double recentExposureDivisor
) {
    public ShadowFeatureConfig {
        requireUnit("qualityPriorMean", qualityPriorMean);
        requirePositive("qualityPriorStrength", qualityPriorStrength);
        requireUnit("categoryTasteShare", categoryTasteShare);
        requirePositive("recentExposureDivisor", recentExposureDivisor);
    }

    public static ShadowFeatureConfig defaults() {
        return new ShadowFeatureConfig(0.70, 20.0, 0.65, 100.0);
    }

    private static void requireUnit(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " 必须在 [0,1] 内");
        }
    }

    private static void requirePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
