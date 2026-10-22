package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.Restaurant;
import java.time.LocalDateTime;
import java.util.List;

/** 用于短期重复惩罚和探索限制的近期饮食历史。 */
public record RecentFoodHistory(List<Entry> entries, LocalDateTime now) {
    public record Entry(String source, String sourcePoiId, String categoryCode,
                        String result, LocalDateTime selectedAt) { }

    public RecentFoodHistory {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static RecentFoodHistory empty(LocalDateTime now) {
        return new RecentFoodHistory(List.of(), now);
    }

}
