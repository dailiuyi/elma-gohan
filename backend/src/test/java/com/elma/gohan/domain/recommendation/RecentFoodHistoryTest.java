package com.elma.gohan.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecentFoodHistoryTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);

    @Test
    void strongestSameRestaurantPenaltyWins() {
        var restaurant = TestRestaurants.full("p1", 4.5, 300, 30);
        var history = new RecentFoodHistory(List.of(
                new RecentFoodHistory.Entry("AMAP", "p1", restaurant.categoryCode(),
                        "DISLIKE", now.minusDays(2)),
                new RecentFoodHistory.Entry("AMAP", "p1", restaurant.categoryCode(),
                        "LIKE", now.minusDays(1))), now);
        assertThat(history.penalty(restaurant)).isEqualTo(40.0);
    }

    @Test
    void consecutiveCategoryDaysUseTwelvePointPenalty() {
        var restaurant = TestRestaurants.full("target", 4.5, 300, 30);
        var history = new RecentFoodHistory(List.of(
                new RecentFoodHistory.Entry("AMAP", "a", restaurant.categoryCode(),
                        "LIKE", now.minusDays(1)),
                new RecentFoodHistory.Entry("AMAP", "b", restaurant.categoryCode(),
                        "NORMAL", now.minusDays(2))), now);
        assertThat(history.penalty(restaurant)).isEqualTo(12.0);
    }
}
