package com.elma.gohan.domain.restaurant;

import java.util.List;

/**
 * 一次推荐请求的搜索条件。minDistance/minBudget 为不包含的下界，
 * radius/maxBudget 为包含的上界；省略下界时兼容旧客户端的累计上限语义。
 */
public record SearchCondition(
        Integer minDistance,
        int radius,
        Integer minBudget,
        Integer maxBudget,
        String category,
        List<String> dislikes
) {
    public static final String CATEGORY_MEAL = "MEAL";
    public static final String CATEGORY_ANY = "ANY";

    public SearchCondition {
        category = CategoryFilter.fromRequest(category).name();
        dislikes = dislikes == null ? List.of() : List.copyOf(dislikes);
    }

    public SearchCondition(int radius, Integer maxBudget, String category, List<String> dislikes) {
        this(null, radius, null, maxBudget, category, dislikes);
    }

    public boolean categoryUnlimited() {
        return categoryFilter() == CategoryFilter.ANY;
    }

    public CategoryFilter categoryFilter() {
        return CategoryFilter.fromRequest(category);
    }
}
