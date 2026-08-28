package com.elma.gohan.domain.recommendation;

/** LowRegretScore 的可重放分项。 */
public record ScoreBreakdown(
        double restaurantQuality,
        double riskSafety,
        double tasteMatch,
        double budgetFit,
        double distanceFit,
        double recentDiversity,
        double recentPenalty,
        double explorationBonus
) { }
