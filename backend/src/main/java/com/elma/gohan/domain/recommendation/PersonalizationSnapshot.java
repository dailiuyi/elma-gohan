package com.elma.gohan.domain.recommendation;

import java.util.List;

/** 候选创建时冻结的个性化解释和分项快照。 */
public record PersonalizationSnapshot(
        double tasteMatchScore,
        double confidence,
        SelectionMode selectionMode,
        List<String> reasons,
        String algorithmVersion,
        ScoreBreakdown scoreBreakdown
) {
    public PersonalizationSnapshot {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static PersonalizationSnapshot neutral(String algorithmVersion) {
        return new PersonalizationSnapshot(50.0, 0.0, SelectionMode.DEFAULT,
                List.of(), algorithmVersion,
                new ScoreBreakdown(0, 0, 50, 0, 0, 100, 0, 0));
    }
}
