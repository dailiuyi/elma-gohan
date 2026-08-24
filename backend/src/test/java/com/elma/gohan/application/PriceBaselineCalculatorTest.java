package com.elma.gohan.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceBaselineCalculatorTest {

    @Test
    void calculatesMedianPerCategoryGroupAndIgnoresSmallGroups() {
        List<Restaurant> restaurants = List.of(
                restaurant("m1", "CHINESE", 10),
                restaurant("m2", "FOREIGN", 20),
                restaurant("m3", "FOOD_COURT", 30),
                restaurant("m4", "CHINESE", 40),
                restaurant("m5", "FOREIGN", 1000),
                restaurant("f1", "SNACK", 10),
                restaurant("f2", "SNACK", 20),
                restaurant("f3", "SNACK", 30),
                restaurant("f4", "SNACK", 40));

        var baselines = PriceBaselineCalculator.byCategoryGroup(restaurants, 5);

        assertThat(baselines).containsEntry("MEAL", 30.0);
        assertThat(baselines).doesNotContainKey("FAST_FOOD");
    }

    @Test
    void evenSizedGroupUsesAverageOfMiddleValuesAndIgnoresMissingPrices() {
        List<Restaurant> restaurants = List.of(
                restaurant("a", "CHINESE", 10),
                restaurant("b", "CHINESE", 20),
                restaurant("c", "CHINESE", 30),
                restaurant("d", "CHINESE", 100),
                restaurant("e", "CHINESE", null));

        assertThat(PriceBaselineCalculator.byCategoryGroup(restaurants, 4))
                .containsEntry("MEAL", 25.0);
    }

    private Restaurant restaurant(String id, String category, Integer price) {
        return new Restaurant(null, "AMAP", id, "餐厅" + id, 28.0, 112.0, 300,
                category, category, 4.5, 100, price, BusinessStatus.OPEN,
                "09:00-21:00", "地址", DataCompleteness.FULL);
    }
}
