package com.elma.gohan.controller.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import com.elma.gohan.domain.recommendation.FlavorTag;

/** 严格对齐 contracts/openapi.yaml 的 SubmitFeedbackRequest(只有 result)。 */
public record SubmitFeedbackRequest(
        @NotNull(message = "必填") Result result,
        @Size(max = 3, message = "最多选择 3 个口味标签") List<FlavorTag> flavorTags
) {
    public enum Result { LIKE, NORMAL, DISLIKE }

    public SubmitFeedbackRequest(Result result) {
        this(result, List.of());
    }

    public SubmitFeedbackRequest {
        flavorTags = flavorTags == null ? List.of() : List.copyOf(flavorTags);
    }
}
