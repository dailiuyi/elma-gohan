package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.Restaurant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RecentFoodHistory(List<Entry> entries, LocalDateTime now) {
    public record Entry(String source, String sourcePoiId, String categoryCode,
                        String result, LocalDateTime selectedAt) { }

    public RecentFoodHistory {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static RecentFoodHistory empty(LocalDateTime now) {
        return new RecentFoodHistory(List.of(), now);
    }

    public double penalty(Restaurant restaurant) {
        LocalDateTime sevenDays = now.minusDays(7);
        boolean sameDislike = entries.stream().anyMatch(e -> sameRestaurant(e, restaurant)
                && !e.selectedAt().isBefore(sevenDays) && "DISLIKE".equals(e.result()));
        if (sameDislike) return 40.0;
        boolean sameRestaurant = entries.stream().anyMatch(e -> sameRestaurant(e, restaurant)
                && !e.selectedAt().isBefore(sevenDays));
        if (sameRestaurant) return 30.0;
        LocalDate today = now.toLocalDate();
        boolean yesterday = ateCategoryOn(restaurant.categoryCode(), today.minusDays(1));
        boolean twoDaysAgo = ateCategoryOn(restaurant.categoryCode(), today.minusDays(2));
        if (yesterday && twoDaysAgo) return 12.0;
        if (yesterday) return 5.0;
        return 0.0;
    }

    public double diversityScore(Restaurant restaurant) {
        long count = entries.stream().filter(e -> sameCategory(e.categoryCode(), restaurant.categoryCode())
                && !e.selectedAt().isBefore(now.minusDays(7))).count();
        return Math.max(0.0, 100.0 - Math.min(100.0, count * 25.0));
    }

    public boolean ateCategoryWithinDays(String category, int days) {
        return entries.stream().anyMatch(e -> sameCategory(e.categoryCode(), category)
                && !e.selectedAt().isBefore(now.minusDays(days)));
    }

    public boolean dislikedCategoryWithinDays(String category, int days) {
        return entries.stream().anyMatch(e -> sameCategory(e.categoryCode(), category)
                && "DISLIKE".equals(e.result()) && !e.selectedAt().isBefore(now.minusDays(days)));
    }

    private boolean ateCategoryOn(String category, LocalDate date) {
        return entries.stream().anyMatch(e -> sameCategory(e.categoryCode(), category)
                && e.selectedAt().toLocalDate().equals(date));
    }
    private static boolean sameRestaurant(Entry e, Restaurant r) {
        return e.source().equalsIgnoreCase(r.source()) && e.sourcePoiId().equals(r.sourcePoiId());
    }
    private static boolean sameCategory(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
