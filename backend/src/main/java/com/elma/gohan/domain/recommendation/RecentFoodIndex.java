package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.Restaurant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 每次排序批次遍历一次历史，按门店和品类索引；评分与探索共用冻结结果。 */
public final class RecentFoodIndex {
    public record Features(double penalty, double diversityScore, boolean ateCategoryWithinSevenDays,
                           boolean dislikedCategoryWithinThirtyDays) { }
    private record Store(String source, String poiId) { }
    private final Map<Store, Double> storePenalties;
    private final Map<String, Integer> categoryCounts;
    private final Set<String> yesterday;
    private final Set<String> twoDaysAgo;
    private final Set<String> recentlyDisliked;

    public RecentFoodIndex(RecentFoodHistory history) {
        Map<Store, Double> penalties = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<String> yesterdayCategories = new HashSet<>();
        Set<String> twoDaysAgoCategories = new HashSet<>();
        Set<String> disliked = new HashSet<>();
        LocalDateTime sevenDays = history.now().minusDays(7);
        LocalDateTime thirtyDays = history.now().minusDays(30);
        for (var entry : history.entries()) {
            String category = normalized(entry.categoryCode());
            if (category != null && "DISLIKE".equals(entry.result()) && !entry.selectedAt().isBefore(thirtyDays))
                disliked.add(category);
            if (entry.selectedAt().isBefore(sevenDays)) continue;
            penalties.merge(new Store(normalized(entry.source()), entry.sourcePoiId()),
                    "DISLIKE".equals(entry.result()) ? 40.0 : 30.0, Math::max);
            if (category == null) continue;
            counts.merge(category, 1, Integer::sum);
            if (entry.selectedAt().toLocalDate().equals(history.now().toLocalDate().minusDays(1)))
                yesterdayCategories.add(category);
            if (entry.selectedAt().toLocalDate().equals(history.now().toLocalDate().minusDays(2)))
                twoDaysAgoCategories.add(category);
        }
        storePenalties = Map.copyOf(penalties);
        categoryCounts = Map.copyOf(counts);
        yesterday = Set.copyOf(yesterdayCategories);
        twoDaysAgo = Set.copyOf(twoDaysAgoCategories);
        recentlyDisliked = Set.copyOf(disliked);
    }

    public Features forRestaurant(Restaurant restaurant) {
        String category = normalized(restaurant.categoryCode());
        int count = category == null ? 0 : categoryCounts.getOrDefault(category, 0);
        boolean ateYesterday = category != null && yesterday.contains(category);
        boolean ateTwoDaysAgo = category != null && twoDaysAgo.contains(category);
        double categoryPenalty = ateYesterday ? (ateTwoDaysAgo ? 12.0 : 5.0) : 0.0;
        double penalty = storePenalties.getOrDefault(new Store(normalized(restaurant.source()),
                restaurant.sourcePoiId()), categoryPenalty);
        return new Features(penalty, Math.max(0.0, 100.0 - Math.min(100.0, count * 25.0)),
                count > 0, category != null && recentlyDisliked.contains(category));
    }

    private static String normalized(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
