package com.elma.gohan.domain.recommendation.v05;

/** SafeRegret v0.5 的纯领域参数，数值均不从现有配置类隐式读取。 */
public record SafeRegretConfig(
        Weights weights,
        double worstRegretBlend,
        double uncertaintyPenalty,
        double missingUtilityPrior,
        double walkingSpeedMetersPerMinute,
        double distanceHalfLifeMinutes,
        double nearTieScoreDelta,
        double softmaxTemperature,
        int poolSize,
        double maxDiversityLoss,
        double diversityPenaltyPoints,
        int priceBandWidth,
        int distanceBandWidth
) {
    public SafeRegretConfig {
        if (weights == null) throw new IllegalArgumentException("weights 不能为空");
        requireUnit("worstRegretBlend", worstRegretBlend);
        requireUnit("uncertaintyPenalty", uncertaintyPenalty);
        requireUnit("missingUtilityPrior", missingUtilityPrior);
        requirePositive("walkingSpeedMetersPerMinute", walkingSpeedMetersPerMinute);
        requirePositive("distanceHalfLifeMinutes", distanceHalfLifeMinutes);
        requireNonNegative("nearTieScoreDelta", nearTieScoreDelta);
        requirePositive("softmaxTemperature", softmaxTemperature);
        if (poolSize < 1) throw new IllegalArgumentException("poolSize 必须大于 0");
        requireNonNegative("maxDiversityLoss", maxDiversityLoss);
        requireNonNegative("diversityPenaltyPoints", diversityPenaltyPoints);
        if (priceBandWidth < 1) throw new IllegalArgumentException("priceBandWidth 必须大于 0");
        if (distanceBandWidth < 1) {
            throw new IllegalArgumentException("distanceBandWidth 必须大于 0");
        }
    }

    public static SafeRegretConfig defaults() {
        return new SafeRegretConfig(
                new Weights(0.30, 0.25, 0.20, 0.15, 0.10),
                0.55,
                0.10,
                0.50,
                80.0,
                12.0,
                2.0,
                1.5,
                6,
                5.0,
                8.0,
                30,
                500);
    }

    /** 各遗憾维度的基础权重；Taste 会再乘以候选画像可信度。 */
    public record Weights(
            double safety,
            double quality,
            double taste,
            double budget,
            double distance
    ) {
        public Weights {
            requireNonNegative("safety", safety);
            requireNonNegative("quality", quality);
            requireNonNegative("taste", taste);
            requireNonNegative("budget", budget);
            requireNonNegative("distance", distance);
            if (safety + quality + taste + budget + distance <= 0.0) {
                throw new IllegalArgumentException("至少一个维度必须具有正权重");
            }
        }
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

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " 不能为负数");
        }
    }
}
