package com.elma.gohan.domain.recommendation;

import java.util.List;

/** LowRegretScore 及其个性化计算明细。 */
public record PersonalizedScore(
        double score,
        double tasteMatchScore,
        double confidence,
        List<String> reasons,
        List<String> personalizationReasons,
        ScoreBreakdown breakdown
) { }
