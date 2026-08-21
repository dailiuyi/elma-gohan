package com.elma.gohan.controller.api;

import com.elma.gohan.domain.recommendation.BehaviorType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SubmitBehaviorRequest(
        @NotNull(message = "必填") UUID eventId,
        @NotNull(message = "必填") UUID restaurantId,
        @NotNull(message = "必填") BehaviorType type
) { }
