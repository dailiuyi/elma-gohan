package com.elma.gohan.domain.recommendation.v05;

import java.util.Set;

/** SafeRegret v0.5 的纯领域候选输入，不依赖具体 RiskPosterior 实现。 */
public record SafeRegretCandidate(
        String candidateId,
        RiskView risk,
        Double qualityUtility,
        Integer averagePrice,
        int distanceMeters,
        Double tasteUtility,
        double tasteConfidence,
        double recentExposurePenalty,
        String categoryKey,
        Set<String> flavorTags
) {
    public SafeRegretCandidate {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId 不能为空");
        }
        if (risk == null) throw new IllegalArgumentException("risk 不能为空");
        requireNullableUnit("qualityUtility", qualityUtility);
        requireNullableUnit("tasteUtility", tasteUtility);
        requireUnit("tasteConfidence", tasteConfidence);
        requireUnit("recentExposurePenalty", recentExposurePenalty);
        if (distanceMeters < 0) throw new IllegalArgumentException("distanceMeters 不能为负数");
        if (averagePrice != null && averagePrice < 0) {
            throw new IllegalArgumentException("averagePrice 不能为负数");
        }
        if (tasteUtility == null && tasteConfidence > 0.0) {
            throw new IllegalArgumentException("缺少 tasteUtility 时 tasteConfidence 必须为 0");
        }
        flavorTags = flavorTags == null ? Set.of()
                : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                        flavorTags.stream().sorted().toList()));
    }

    /** 风险后验向推荐域暴露的最小稳定视图。 */
    public record RiskView(
            double posteriorMean,
            double conservativeRisk,
            double confidence,
            boolean trustedSafe,
            boolean blocked
    ) {
        public RiskView {
            requireUnit("posteriorMean", posteriorMean);
            requireUnit("conservativeRisk", conservativeRisk);
            requireUnit("confidence", confidence);
            if (conservativeRisk < posteriorMean) {
                throw new IllegalArgumentException(
                        "conservativeRisk 不能低于 posteriorMean");
            }
            if (trustedSafe && blocked) {
                throw new IllegalArgumentException("风险不能同时是可信安全和阻断");
            }
        }
    }

    private static void requireNullableUnit(String name, Double value) {
        if (value != null) requireUnit(name, value);
    }

    private static void requireUnit(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " 必须在 [0,1] 内");
        }
    }
}
