package com.elma.gohan.domain.recommendation.v05;

import java.util.List;
import java.util.Map;

/** 一次可确定性重放的 SafeRegret 评分与候选池选择结果。 */
public record SafeRegretDecision(
        List<ScoredCandidate> rankedCandidates,
        List<Selection> pool,
        Map<String, Double> firstChoicePropensities,
        long randomSeed
) {
    public SafeRegretDecision {
        rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        pool = pool == null ? List.of() : List.copyOf(pool);
        firstChoicePropensities = firstChoicePropensities == null
                ? Map.of() : Map.copyOf(firstChoicePropensities);
    }

    public record ScoredCandidate(
            SafeRegretCandidate candidate,
            double score,
            Breakdown breakdown
    ) { }

    public record Breakdown(
            BudgetStatus budgetStatus,
            double safetyUtility,
            double qualityUtility,
            double tasteUtility,
            double budgetUtility,
            double distanceUtility,
            double worstWeightedRegret,
            double weightedAverageRegret,
            double unifiedUncertainty,
            double uncertaintyPenalty,
            double recentExposurePenalty,
            double robustRegret
    ) { }

    public record Selection(
            int slot,
            ScoredCandidate candidate,
            double propensity,
            double objective,
            String reason
    ) { }

    public enum BudgetStatus {
        UNCONSTRAINED,
        SATISFIED,
        UNKNOWN
    }
}
