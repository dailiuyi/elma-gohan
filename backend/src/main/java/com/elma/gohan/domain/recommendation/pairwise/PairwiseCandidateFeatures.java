package com.elma.gohan.domain.recommendation.pairwise;

/**
 * 参与成对比较的稳定特征快照。
 *
 * <p>口味标签有意不放在候选快照中，避免把餐厅推断标签误当成用户显式口味选择。
 */
public record PairwiseCandidateFeatures(
        String category,
        String priceBand,
        String distanceBand) {

    public PairwiseCandidateFeatures {
        category = normalize(category);
        priceBand = normalize(priceBand);
        distanceBand = normalize(distanceBand);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
