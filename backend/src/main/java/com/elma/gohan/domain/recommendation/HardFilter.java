package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 硬条件过滤:距离 / 预算 / 品类 / 营业状态 / 不想吃关键词。
 * averagePrice 缺失且预算非空时不剔除(数据缺失不等于超预算,交给 RiskEngine 加分)。
 */
@Component
public class HardFilter {

    public List<Restaurant> filter(List<Restaurant> restaurants, SearchCondition condition) {
        return restaurants.stream()
                .filter(r -> passes(r, condition))
                .toList();
    }

    private boolean passes(Restaurant r, SearchCondition c) {
        if (c.minDistance() != null && r.distanceMeters() <= c.minDistance()) {
            return false;
        }
        if (r.distanceMeters() > c.radius()) {
            return false;
        }
        if (r.averagePrice() != null) {
            if (c.minBudget() != null && r.averagePrice() <= c.minBudget()) {
                return false;
            }
            if (c.maxBudget() != null && r.averagePrice() > c.maxBudget()) {
                return false;
            }
        }
        if (!c.categoryFilter().matches(r.categoryCode())) {
            return false;
        }
        if (r.businessStatus() == BusinessStatus.CLOSED) {
            return false;
        }
        return !hitsDislike(r, c.dislikes());
    }

    /** V0.1 简单词匹配:关键词命中名称或品类 label 即剔除。 */
    private boolean hitsDislike(Restaurant r, List<String> dislikes) {
        if (dislikes == null || dislikes.isEmpty()) {
            return false;
        }
        String name = r.name() == null ? "" : r.name();
        String label = r.categoryLabel() == null ? "" : r.categoryLabel();
        return dislikes.stream().anyMatch(d -> name.contains(d) || label.contains(d));
    }
}
