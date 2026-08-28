package com.elma.gohan.controller.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreateRecommendationRequestTest {

    @Test
    @DisplayName("不想吃关键词在服务端支持空格、逗号和换行分隔并去重")
    void normalizesDislikes() {
        var request = new CreateRecommendationRequest(
                28.2282, 112.9388, 1000, null, null, null, "CHINESE",
                List.of("香菜 内脏", "内脏，肥肉\nSPICY", "spicy"));

        assertThat(request.dislikes()).containsExactly("香菜", "内脏", "肥肉", "SPICY");
    }

    @Test
    @DisplayName("区间下界字段按请求原样保留")
    void keepsRangeLowerBounds() {
        var request = new CreateRecommendationRequest(
                28.2282, 112.9388, 1000, 500, 40, 20, "MEAL", List.of());

        assertThat(request.minDistance()).isEqualTo(500);
        assertThat(request.minBudget()).isEqualTo(20);
    }

    @Test
    @DisplayName("粉面、粉、面可在同一个不想吃输入中分别保留")
    void keepsNoodleDislikeAliases() {
        var request = new CreateRecommendationRequest(
                28.2282, 112.9388, 1000, null, null, null, "MEAL",
                List.of("粉面，粉 面"));

        assertThat(request.dislikes()).containsExactly("粉面", "粉", "面");
    }

    @Test
    @DisplayName("修改条件时保留需要排除的当前餐厅 ID")
    void keepsExcludedRestaurantId() {
        String restaurantId = "8322a6eb-186b-4be7-b4e5-980c9ef93042";
        var request = new CreateRecommendationRequest(
                28.2282, 112.9388, 1000, 500, 40, 20, "MEAL", List.of(), restaurantId);

        assertThat(request.excludeRestaurantId()).isEqualTo(restaurantId);
    }
}
