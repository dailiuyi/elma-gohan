package com.elma.gohan.controller.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 严格对齐 contracts/openapi.yaml 的 CreateRecommendationRequest。 */
public record CreateRecommendationRequest(
        @NotNull(message = "必填")
        @Min(value = -90, message = "必须在 -90 到 90 之间")
        @Max(value = 90, message = "必须在 -90 到 90 之间")
        Double latitude,

        @NotNull(message = "必填")
        @Min(value = -180, message = "必须在 -180 到 180 之间")
        @Max(value = 180, message = "必须在 -180 到 180 之间")
        Double longitude,

        @Min(value = 500, message = "只能是 500、1000、2000 或 3000")
        @Max(value = 3000, message = "只能是 500、1000、2000 或 3000")
        Integer radius,

        @Min(value = 0, message = "必须大于等于 0")
        @Max(value = 2999, message = "必须小于 3000")
        Integer minDistance,

        @Min(value = 1, message = "必须大于等于 1")
        @Max(value = 10000, message = "必须小于等于 10000")
        Integer maxBudget,

        @Min(value = 1, message = "必须大于等于 1")
        @Max(value = 9999, message = "必须小于 10000")
        Integer minBudget,

        @Pattern(regexp = "^(MEAL|CHINESE|HOT_POT|BARBECUE|NOODLES|FAST_FOOD|WESTERN|JAPANESE_KOREAN|DESSERT_DRINK|ANY)$",
                message = "品类值不受支持")
        String category,

        @Size(max = 10, message = "最多 10 个")
        List<@NotBlank(message = "不能为空")
        @Size(max = 30, message = "长度不能超过 30") String> dislikes,

        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
                message = "必须是合法的 UUID")
        String excludeRestaurantId
) {
    public CreateRecommendationRequest {
        dislikes = normalizeDislikes(dislikes);
    }

    public CreateRecommendationRequest(Double latitude, Double longitude, Integer radius,
                                       Integer maxBudget, String category, List<String> dislikes) {
        this(latitude, longitude, radius, null, maxBudget, null, category, dislikes, null);
    }

    public CreateRecommendationRequest(Double latitude, Double longitude, Integer radius,
                                       Integer minDistance, Integer maxBudget, Integer minBudget,
                                       String category, List<String> dislikes) {
        this(latitude, longitude, radius, minDistance, maxBudget, minBudget,
                category, dislikes, null);
    }

    private static List<String> normalizeDislikes(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) return List.of();
        var unique = new LinkedHashMap<String, String>();
        for (String rawValue : rawValues) {
            if (rawValue == null || rawValue.isBlank()) {
                unique.putIfAbsent("", "");
                continue;
            }
            boolean producedValue = false;
            for (String segment : rawValue.split("[,，\\s]+")) {
                String value = segment.trim();
                if (!value.isEmpty()) {
                    unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
                    producedValue = true;
                }
            }
            if (!producedValue) unique.putIfAbsent("", "");
        }
        return List.copyOf(unique.values());
    }

    @JsonProperty("maxBudget")
    public Integer maxBudget() {
        return maxBudget;
    }

    @JsonProperty("minDistance")
    public Integer minDistance() {
        return minDistance;
    }

    @JsonProperty("minBudget")
    public Integer minBudget() {
        return minBudget;
    }
}
