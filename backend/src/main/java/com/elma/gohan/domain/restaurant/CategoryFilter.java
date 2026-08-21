package com.elma.gohan.domain.restaurant;

import java.util.Locale;
import java.util.Set;

/**
 * 面向用户的品类筛选。MEAL 保持默认兜底，具体类型用于可选纠偏。
 */
public enum CategoryFilter {
    MEAL(Set.of("CHINESE", "HOT_POT", "BARBECUE", "NOODLES", "WESTERN",
            "JAPANESE_KOREAN", "FOREIGN", "FOOD_COURT")),
    CHINESE(Set.of("CHINESE", "HOT_POT", "BARBECUE", "NOODLES")),
    HOT_POT(Set.of("HOT_POT")),
    BARBECUE(Set.of("BARBECUE")),
    NOODLES(Set.of("NOODLES")),
    FAST_FOOD(Set.of("SNACK")),
    WESTERN(Set.of("WESTERN")),
    JAPANESE_KOREAN(Set.of("JAPANESE_KOREAN")),
    DESSERT_DRINK(Set.of("DESSERT", "COFFEE", "TEA", "DRINKS")),
    ANY(Set.of());

    private static final String OTHER_GROUP = "OTHER";

    private final Set<String> restaurantCategoryCodes;

    CategoryFilter(Set<String> restaurantCategoryCodes) {
        this.restaurantCategoryCodes = restaurantCategoryCodes;
    }

    /** 请求缺省时直接进入正餐主流程,不要求用户先做选择。 */
    public static CategoryFilter fromRequest(String code) {
        if (code == null || code.isBlank()) {
            return MEAL;
        }
        return valueOf(code.toUpperCase(Locale.ROOT));
    }

    public boolean matches(String restaurantCategoryCode) {
        if (this == ANY) {
            return true;
        }
        return restaurantCategoryCode != null
                && restaurantCategoryCodes.contains(restaurantCategoryCode.toUpperCase(Locale.ROOT));
    }

    /** ANY 重排时使用的大类键;未识别细品类单独归入 OTHER。 */
    public static String groupCodeForRestaurant(String restaurantCategoryCode) {
        if (restaurantCategoryCode == null) {
            return OTHER_GROUP;
        }
        String normalized = restaurantCategoryCode.toUpperCase(Locale.ROOT);
        for (CategoryFilter filter : values()) {
            if (filter != ANY && filter.restaurantCategoryCodes.contains(normalized)) {
                return filter.name();
            }
        }
        return OTHER_GROUP;
    }
}
