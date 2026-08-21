package com.elma.gohan.domain.recommendation;

import com.elma.gohan.application.FlavorFeatureService;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** 当前条件、长期画像、可信口味标签与近期历史。 */
public record UserPreference(
        SearchCondition condition,
        TasteProfile tasteProfile,
        Map<String, Set<FlavorTag>> flavorTagsByRestaurant,
        RecentFoodHistory recentHistory
) {
    public UserPreference {
        tasteProfile = tasteProfile == null ? TasteProfile.empty() : tasteProfile;
        flavorTagsByRestaurant = flavorTagsByRestaurant == null ? Map.of() : Map.copyOf(flavorTagsByRestaurant);
        recentHistory = recentHistory == null
                ? RecentFoodHistory.empty(LocalDateTime.now()) : recentHistory;
    }
    public UserPreference(SearchCondition condition) {
        this(condition, TasteProfile.empty(), Map.of(), RecentFoodHistory.empty(LocalDateTime.now()));
    }
    public UserPreference(SearchCondition condition, TasteProfile tasteProfile) {
        this(condition, tasteProfile, Map.of(), RecentFoodHistory.empty(LocalDateTime.now()));
    }
    public Set<FlavorTag> flavorTags(Restaurant restaurant) {
        return flavorTagsByRestaurant.getOrDefault(FlavorFeatureService.key(restaurant), Set.of());
    }
}
