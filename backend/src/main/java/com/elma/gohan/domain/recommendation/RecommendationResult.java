package com.elma.gohan.domain.recommendation;

import java.util.List;

/**
 * 推荐结果:有序候选池(slot 1 = A 为当前推荐,依序 B/C),大小 1~poolSize。
 */
public record RecommendationResult(
        List<RestaurantCandidate> pool,
        String algorithmVersion,
        long randomSeed,
        List<SelectionCandidate> selectionSnapshot
) {
    public RecommendationResult {
        pool = pool == null ? List.of() : List.copyOf(pool);
        selectionSnapshot = selectionSnapshot == null ? List.of() : List.copyOf(selectionSnapshot);
    }

    public RecommendationResult(List<RestaurantCandidate> pool, String algorithmVersion) {
        this(pool, algorithmVersion, 0L, List.of());
    }
}
