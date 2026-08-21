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
                28.2282, 112.9388, 1000, null, "CHINESE",
                List.of("香菜 内脏", "内脏，肥肉\nSPICY", "spicy"));

        assertThat(request.dislikes()).containsExactly("香菜", "内脏", "肥肉", "SPICY");
    }
}
