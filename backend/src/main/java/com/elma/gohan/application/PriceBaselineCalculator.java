package com.elma.gohan.application;

import com.elma.gohan.domain.restaurant.CategoryFilter;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按产品品类组计算稳健价格基线。 */
final class PriceBaselineCalculator {

    private PriceBaselineCalculator() {
    }

    static Map<String, Double> byCategoryGroup(List<Restaurant> restaurants, int minPoolSize) {
        Map<String, List<Integer>> pricesByGroup = new LinkedHashMap<>();
        for (Restaurant restaurant : restaurants) {
            if (restaurant.averagePrice() != null) {
                pricesByGroup.computeIfAbsent(groupKey(restaurant), ignored -> new ArrayList<>())
                        .add(restaurant.averagePrice());
            }
        }
        int requiredSamples = Math.max(1, minPoolSize);
        Map<String, Double> baselines = new LinkedHashMap<>();
        pricesByGroup.forEach((group, prices) -> {
            if (prices.size() >= requiredSamples) {
                baselines.put(group, median(prices));
            }
        });
        return Map.copyOf(baselines);
    }

    static String groupKey(Restaurant restaurant) {
        return CategoryFilter.groupCodeForRestaurant(restaurant.categoryCode());
    }

    private static double median(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }
}
