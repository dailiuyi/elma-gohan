package com.elma.gohan.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HardFilterTest {

    private final HardFilter filter = new HardFilter();

    private SearchCondition condition(Integer radius, Integer budget, String category,
                                      List<String> dislikes) {
        return new SearchCondition(radius, budget, category, dislikes);
    }

    private SearchCondition rangeCondition(Integer minDistance, int radius,
                                           Integer minBudget, Integer maxBudget) {
        return new SearchCondition(minDistance, radius, minBudget, maxBudget, "ANY", List.of());
    }

    @Test
    @DisplayName("距离超过半径剔除")
    void distanceFilter() {
        var list = List.of(TestRestaurants.full("a", 4.5, 500), TestRestaurants.full("b", 4.5, 501));
        assertThat(filter.filter(list, condition(500, null, "ANY", List.of())))
                .extracting(Restaurant::sourcePoiId)
                .containsExactly("a");
    }

    @Test
    @DisplayName("距离区间下界不包含、上界包含且相邻区间不重叠")
    void distanceBandBoundaries() {
        var restaurants = List.of(
                TestRestaurants.full("d500", 4.5, 500),
                TestRestaurants.full("d501", 4.5, 501),
                TestRestaurants.full("d1000", 4.5, 1000),
                TestRestaurants.full("d1001", 4.5, 1001),
                TestRestaurants.full("d2000", 4.5, 2000),
                TestRestaurants.full("d2001", 4.5, 2001),
                TestRestaurants.full("d3000", 4.5, 3000));

        assertThat(filter.filter(restaurants, rangeCondition(null, 500, null, null)))
                .extracting(Restaurant::sourcePoiId).containsExactly("d500");
        assertThat(filter.filter(restaurants, rangeCondition(500, 1000, null, null)))
                .extracting(Restaurant::sourcePoiId).containsExactly("d501", "d1000");
        assertThat(filter.filter(restaurants, rangeCondition(1000, 2000, null, null)))
                .extracting(Restaurant::sourcePoiId).containsExactly("d1001", "d2000");
        assertThat(filter.filter(restaurants, rangeCondition(2000, 3000, null, null)))
                .extracting(Restaurant::sourcePoiId).containsExactly("d2001", "d3000");
    }

    @Test
    @DisplayName("人均价高于预算剔除;价格缺失且预算非空时保留")
    void budgetFilter() {
        var expensive = TestRestaurants.full("a", 4.5, 300, 50);
        var noPrice = TestRestaurants.full("b", 4.5, 300, null);
        var cheap = TestRestaurants.full("c", 4.5, 300, 20);
        var result = filter.filter(List.of(expensive, noPrice, cheap), condition(1000, 40, "ANY", List.of()));
        assertThat(result).extracting(Restaurant::sourcePoiId).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    @DisplayName("预算区间下界不包含、上界包含且价格缺失继续保留")
    void budgetBandBoundaries() {
        var restaurants = List.of(
                TestRestaurants.full("p20", 4.5, 300, 20),
                TestRestaurants.full("p21", 4.5, 300, 21),
                TestRestaurants.full("p40", 4.5, 300, 40),
                TestRestaurants.full("p41", 4.5, 300, 41),
                TestRestaurants.full("p70", 4.5, 300, 70),
                TestRestaurants.full("p71", 4.5, 300, 71),
                TestRestaurants.full("unknown", 4.5, 300, null));

        assertThat(filter.filter(restaurants, rangeCondition(null, 1000, null, 20)))
                .extracting(Restaurant::sourcePoiId).containsExactly("p20", "unknown");
        assertThat(filter.filter(restaurants, rangeCondition(null, 1000, 20, 40)))
                .extracting(Restaurant::sourcePoiId).containsExactly("p21", "p40", "unknown");
        assertThat(filter.filter(restaurants, rangeCondition(null, 1000, 40, 70)))
                .extracting(Restaurant::sourcePoiId).containsExactly("p41", "p70", "unknown");
        assertThat(filter.filter(restaurants, rangeCondition(null, 1000, 70, null)))
                .extracting(Restaurant::sourcePoiId).containsExactly("p71", "unknown");
    }

    @Test
    @DisplayName("品类:缺省正餐;大类和细分类均可过滤")
    void categoryFilter() {
        var chinese = TestRestaurants.full("a", 4.5, 300);
        var snack = new Restaurant(null, "AMAP", "b", "快餐", 28.0, 112.0, 300,
                "SNACK", "小吃快餐", 4.5, 100, 30,
                BusinessStatus.UNKNOWN, "09:00-21:00", "地址", com.elma.gohan.domain.restaurant.DataCompleteness.FULL);
        var dessert = new Restaurant(null, "AMAP", "c", "甜品", 28.0, 112.0, 300,
                "DESSERT", "蛋糕甜品", 4.5, 100, 30,
                BusinessStatus.UNKNOWN, "09:00-21:00", "地址", com.elma.gohan.domain.restaurant.DataCompleteness.FULL);
        var hotPot = new Restaurant(null, "AMAP", "d", "火锅", 28.0, 112.0, 300,
                "HOT_POT", "火锅", 4.5, 100, 30,
                BusinessStatus.UNKNOWN, "09:00-21:00", "地址", com.elma.gohan.domain.restaurant.DataCompleteness.FULL);
        var western = new Restaurant(null, "AMAP", "e", "西餐", 28.0, 112.0, 300,
                "WESTERN", "西餐", 4.5, 100, 30,
                BusinessStatus.UNKNOWN, "09:00-21:00", "地址", com.elma.gohan.domain.restaurant.DataCompleteness.FULL);
        var restaurants = List.of(chinese, snack, dessert, hotPot, western);

        assertThat(filter.filter(restaurants, condition(1000, null, null, List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("a", "d", "e");
        assertThat(filter.filter(restaurants, condition(1000, null, "MEAL", List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("a", "d", "e");
        assertThat(filter.filter(restaurants, condition(1000, null, "CHINESE", List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("a", "d");
        assertThat(filter.filter(restaurants, condition(1000, null, "HOT_POT", List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("d");
        assertThat(filter.filter(restaurants, condition(1000, null, "WESTERN", List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("e");
        assertThat(filter.filter(restaurants, condition(1000, null, "FAST_FOOD", List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("b");
        assertThat(filter.filter(restaurants, condition(1000, null, "DESSERT_DRINK", List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("c");
        assertThat(filter.filter(restaurants, condition(1000, null, "ANY", List.of())))
                .hasSize(5);
    }

    @Test
    @DisplayName("营业状态 CLOSED 剔除,UNKNOWN 保留")
    void businessStatusFilter() {
        var closed = new Restaurant(null, "AMAP", "a", "餐厅", 28.0, 112.0, 300,
                "CHINESE", "中餐厅", 4.5, 100, 30,
                BusinessStatus.CLOSED, "09:00-21:00", "地址", com.elma.gohan.domain.restaurant.DataCompleteness.FULL);
        var unknown = TestRestaurants.full("b", 4.5, 300);
        assertThat(filter.filter(List.of(closed, unknown), condition(1000, null, "ANY", List.of())))
                .extracting(Restaurant::sourcePoiId).containsExactly("b");
    }

    @Test
    @DisplayName("dislikes:命中名称或品类 label 剔除")
    void dislikeFilter() {
        var beefNoodle = TestRestaurants.full("a", "老街牛肉粉", 4.5, 300, 30);
        var dessert = new Restaurant(null, "AMAP", "b", "甜品店", 28.0, 112.0, 300,
                "DESSERT", "蛋糕甜品", 4.5, 100, 30,
                BusinessStatus.UNKNOWN, "09:00-21:00", "地址", com.elma.gohan.domain.restaurant.DataCompleteness.FULL);
        assertThat(filter.filter(List.of(beefNoodle, dessert), condition(1000, null, "ANY", List.of("牛肉", "香菜"))))
                .extracting(Restaurant::sourcePoiId).containsExactly("b");
    }

    @Test
    void dislikeUsesNfkcButSingleCharacterDoesNotMatchRestaurantNameSubstring() {
        var restaurant = TestRestaurants.full("a", "面对面餐厅", 4.5, 300, 30);
        assertThat(filter.filter(List.of(restaurant),
                condition(1000, null, "ANY", List.of("面"))))
                .extracting(Restaurant::sourcePoiId).containsExactly("a");
        assertThat(filter.filter(List.of(restaurant),
                condition(1000, null, "ANY", List.of("ＣＨＩＮＥＳＥ"))))
                .isEmpty();
    }
}
