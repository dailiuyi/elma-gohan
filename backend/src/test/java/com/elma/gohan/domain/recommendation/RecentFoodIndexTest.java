package com.elma.gohan.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import com.elma.gohan.TestRestaurants;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RecentFoodIndexTest {
    @Test
    void indexedFeaturesMatchHistorySemanticsAcrossBoundaries() {
        var now = LocalDateTime.of(2026, 10, 22, 12, 0);
        Random random = new Random(42);
        List<RecentFoodHistory.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) entries.add(new RecentFoodHistory.Entry(i % 2 == 0 ? "AMAP" : "amap",
                "p" + random.nextInt(70), i % 11 == 0 ? null : "CHINESE",
                i % 3 == 0 ? "DISLIKE" : "LIKE", now.minusHours(random.nextInt(800) - 12)));
        entries.add(new RecentFoodHistory.Entry("AMAP", "edge", "CHINESE", "DISLIKE", now.minusDays(7)));
        entries.add(new RecentFoodHistory.Entry("AMAP", "expired", "CHINESE", "LIKE", now.minusDays(7).minusNanos(1)));
        var index = new RecentFoodIndex(new RecentFoodHistory(entries, now));
        for (String id : List.of("p0", "p69", "new", "edge", "expired")) {
            var restaurant = TestRestaurants.full(id, 4.5, 300);
            var actual = index.forRestaurant(restaurant);
            var sameStore = entries.stream().filter(e -> e.source().equalsIgnoreCase(restaurant.source())
                    && e.sourcePoiId().equals(id) && !e.selectedAt().isBefore(now.minusDays(7))).toList();
            boolean yesterday = entries.stream().anyMatch(e -> "CHINESE".equalsIgnoreCase(e.categoryCode())
                    && e.selectedAt().toLocalDate().equals(now.toLocalDate().minusDays(1)));
            boolean twoDaysAgo = entries.stream().anyMatch(e -> "CHINESE".equalsIgnoreCase(e.categoryCode())
                    && e.selectedAt().toLocalDate().equals(now.toLocalDate().minusDays(2)));
            double penalty = sameStore.stream().anyMatch(e -> "DISLIKE".equals(e.result())) ? 40
                    : !sameStore.isEmpty() ? 30 : yesterday ? (twoDaysAgo ? 12 : 5) : 0;
            long count = entries.stream().filter(e -> "CHINESE".equalsIgnoreCase(e.categoryCode())
                    && !e.selectedAt().isBefore(now.minusDays(7))).count();
            assertThat(actual.penalty()).isEqualTo(penalty);
            assertThat(actual.diversityScore()).isEqualTo(Math.max(0, 100 - Math.min(100, count * 25)));
            assertThat(actual.ateCategoryWithinSevenDays()).isEqualTo(count > 0);
        }
    }
}
