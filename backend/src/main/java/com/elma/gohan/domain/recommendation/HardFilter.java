package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.restaurant.TextNormalizer;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 硬条件过滤:距离 / 预算 / 品类 / 营业状态 / 不想吃关键词。
 * averagePrice 缺失且预算非空时不剔除(数据缺失不等于超预算,交给 RiskEngine 加分)。
 */
@Component
public class HardFilter {

    private static final String NOODLES_CATEGORY = "noodles";
    private static final Set<String> NOODLES_DISLIKE_ALIASES = Set.of(
            "粉", "面", "粉面", "粉面类", "面食", "面条", "米粉", "米线", "河粉",
            "肠粉", "螺蛳粉", "酸辣粉", "土豆粉", "红薯粉", "凉粉", "拉面",
            "牛肉面", "板面", "烩面", "刀削面", "担担面", "热干面", "炸酱面");

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

    /**
     * 用户明确填写的“不想吃”仍是硬排除。文本先做 NFKC 归一化；单字仅允许
     * 精确命中品类，避免“面”误伤“面对面餐厅”之类的店名。
     */
    private boolean hitsDislike(Restaurant r, List<String> dislikes) {
        if (dislikes == null || dislikes.isEmpty()) {
            return false;
        }
        String name = TextNormalizer.normalize(r.name());
        String label = TextNormalizer.normalize(r.categoryLabel());
        String code = TextNormalizer.normalize(r.categoryCode());
        return dislikes.stream()
                .map(TextNormalizer::normalize)
                .filter(d -> !d.isEmpty())
                .anyMatch(d -> d.equals(label) || d.equals(code) || matchesCategoryAlias(d, code)
                        || (d.codePointCount(0, d.length()) >= 2
                        && (name.contains(d) || label.contains(d))));
    }

    private boolean matchesCategoryAlias(String dislike, String categoryCode) {
        return NOODLES_CATEGORY.equals(categoryCode)
                && NOODLES_DISLIKE_ALIASES.contains(dislike);
    }
}
