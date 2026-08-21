package com.elma.gohan.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultRecommendationEngineTest {

    private final RecommendationProperties props = new RecommendationProperties();
    private final DefaultRecommendationEngine engine = new DefaultRecommendationEngine(
            new HardFilter(), new LowRegretScorer(props), props);

    private Map<String, RiskResult> risks(List<Restaurant> restaurants) {
        return restaurants.stream().collect(Collectors.toMap(
                Restaurant::sourcePoiId,
                r -> new RiskResult(10, RiskLevel.LOW, List.of("评分稳定"), "risk-v0.3")));
    }

    @Test
    @DisplayName("8 家合格候选 -> 候选池大小 6,提供 5 次重新选择")
    void poolSizeSix() {
        List<Restaurant> restaurants = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(i -> TestRestaurants.full("p" + i, 4.0 + i * 0.05, 200 + i * 10))
                .toList();
        var result = engine.recommend(restaurants, risks(restaurants),
                new UserPreference(new SearchCondition(1000, null, "ANY", List.of())));
        assertThat(result.pool()).hasSize(6);
        assertThat(result.pool()).extracting(c -> c.restaurant().sourcePoiId())
                .doesNotHaveDuplicates();
        assertThat(result.algorithmVersion()).isEqualTo("recommendation-v0.4");
    }

    @Test
    @DisplayName("ANY 候选充足时六家在三个产品品类间均衡")
    void anyCategoryPoolIsDiversified() {
        List<Restaurant> restaurants = List.of(
                categoryRestaurant("m1", "CHINESE", 4.9),
                categoryRestaurant("m2", "FOREIGN", 4.8),
                categoryRestaurant("m3", "FOOD_COURT", 4.7),
                categoryRestaurant("f1", "SNACK", 4.6),
                categoryRestaurant("f2", "SNACK", 4.5),
                categoryRestaurant("f3", "SNACK", 4.4),
                categoryRestaurant("d1", "DESSERT", 4.9),
                categoryRestaurant("d2", "COFFEE", 4.8),
                categoryRestaurant("d3", "DRINKS", 4.7));

        var result = engine.recommend(restaurants, risks(restaurants),
                new UserPreference(new SearchCondition(1000, null, "ANY", List.of())));

        Map<String, Long> counts = result.pool().stream().collect(Collectors.groupingBy(
                candidate -> com.elma.gohan.domain.restaurant.CategoryFilter.groupCodeForRestaurant(
                        candidate.restaurant().categoryCode()),
                Collectors.counting()));
        assertThat(counts).containsEntry("MEAL", 2L)
                .containsEntry("FAST_FOOD", 2L)
                .containsEntry("DESSERT_DRINK", 2L);
    }

    @Test
    @DisplayName("HIGH 风险候选不进入候选池")
    void highRiskBlocked() {
        Restaurant good = TestRestaurants.full("good", 4.6, 200);
        Restaurant bad = TestRestaurants.full("bad", 4.6, 200);
        var risks = Map.of(
                "good", new RiskResult(10, RiskLevel.LOW, List.of("评分稳定"), "risk-v0.3"),
                "bad", new RiskResult(80, RiskLevel.HIGH, List.of("评分偏低"), "risk-v0.3"));
        var result = engine.recommend(List.of(good, bad), risks,
                new UserPreference(new SearchCondition(1000, null, "ANY", List.of())));
        assertThat(result.pool()).extracting(c -> c.restaurant().sourcePoiId()).containsExactly("good");
    }

    @Test
    @DisplayName("全部被硬过滤 -> 空候选池")
    void emptyWhenAllFiltered() {
        Restaurant far = TestRestaurants.full("far", 4.5, 5000);
        var result = engine.recommend(List.of(far), risks(List.of(far)),
                new UserPreference(new SearchCondition(1000, null, "ANY", List.of())));
        assertThat(result.pool()).isEmpty();
    }

    @Test
    @DisplayName("候选少于池大小:池大小跟随候选数")
    void poolFollowsCandidateCount() {
        List<Restaurant> two = List.of(TestRestaurants.full("a", 4.5, 100),
                TestRestaurants.full("b", 4.5, 200));
        var result = engine.recommend(two, risks(two),
                new UserPreference(new SearchCondition(1000, null, "ANY", List.of())));
        assertThat(result.pool()).hasSize(2);
    }

    @Test
    void explorationOnlySelectsLowRiskNewCategory() {
        RecommendationProperties explorationProps = new RecommendationProperties();
        explorationProps.setExplorationRate(1.0);
        DefaultRecommendationEngine explorationEngine = new DefaultRecommendationEngine(
                new HardFilter(), new LowRegretScorer(explorationProps), explorationProps, () -> 0.0);
        Restaurant familiar = categoryRestaurant("c", "CHINESE", 4.9);
        Restaurant novel = categoryRestaurant("f", "FOREIGN", 4.2);
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 12, 0);
        RecentFoodHistory history = new RecentFoodHistory(List.of(
                new RecentFoodHistory.Entry("AMAP", "old", "CHINESE", "LIKE", now.minusDays(1))), now);
        UserPreference preference = new UserPreference(
                new SearchCondition(1000, null, "ANY", List.of()), TasteProfile.empty(now),
                Map.of(), history);

        var result = explorationEngine.recommend(List.of(familiar, novel),
                risks(List.of(familiar, novel)), preference);

        assertThat(result.pool().get(0).restaurant().sourcePoiId()).isEqualTo("f");
        assertThat(result.pool().get(0).personalization().selectionMode())
                .isEqualTo(SelectionMode.EXPLORATION);
        assertThat(result.pool().get(0).reasons()).contains("低风险的新类型尝试");
    }

    private Restaurant categoryRestaurant(String id, String categoryCode, double rating) {
        return new Restaurant(null, "AMAP", id, "餐厅" + id, 28.0, 112.0, 300,
                categoryCode, categoryCode, rating, 100, 30,
                BusinessStatus.UNKNOWN, "09:00-21:00", "地址", DataCompleteness.FULL);
    }
}
