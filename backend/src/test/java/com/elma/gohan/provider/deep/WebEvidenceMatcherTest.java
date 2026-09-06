package com.elma.gohan.provider.deep;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import org.junit.jupiter.api.Test;

class WebEvidenceMatcherTest {

    private final WebEvidenceMatcher matcher =
            new WebEvidenceMatcher(new DeepEvidenceProperties());

    @Test
    void excludesOtherBranchesAndMallCollections() {
        var restaurant = TestRestaurants.full("a", "老王湘菜馆(大学城店)", 4.6, 50, 40);
        assertThat(matcher.match(restaurant, "老王湘菜馆(五一广场店)", "大学城也有，麓山南路")).isZero();
        assertThat(matcher.match(restaurant, "大学城美食合集：老王湘菜馆", "麓山南路")).isZero();
        assertThat(matcher.match(restaurant, "老王湘菜馆探店", "好吃")).isZero();
    }

    @Test
    void exactNormalizedNameMatchesAndUnrelatedRestaurantDoesNot() {
        Restaurant restaurant = TestRestaurants.full("a", "老王湘菜馆（大学城店）",
                4.6, 500, 42);

        assertThat(matcher.match(restaurant,
                "老王湘菜馆大学城店到底值不值得排队", "麓山南路本地湘菜"))
                .isGreaterThanOrEqualTo(0.95);
        assertThat(matcher.match(restaurant,
                "老李湘菜馆探店", "另一家餐厅"))
                .isZero();
    }

    @Test
    void fuzzyNameRequiresAddressEvidence() {
        Restaurant restaurant = TestRestaurants.full("a", "大学城湘味家常菜馆",
                4.6, 500, 42);

        assertThat(matcher.match(restaurant, "大学城湘味家常菜", "麓山南路 1 号"))
                .isGreaterThan(0.0);
        assertThat(matcher.match(restaurant, "大学城湘味家常菜", "五一广场"))
                .isZero();
    }

    @Test
    void searchLocationPrefersShortStoreQualifierAndCompactsAddress() {
        assertThat(matcher.searchLocationKeyword(
                "甄钵炉子常德菜馆（麓谷·新长海中心店）",
                "麓谷大道627号麓谷·新长海中心A2栋14楼"))
                .isEqualTo("麓谷新长海中心");
        assertThat(matcher.searchLocationKeyword(
                "甄钵炉子常德菜馆",
                "麓谷大道627号麓谷·新长海中心A2栋14楼"))
                .isEqualTo("新长海中心");
    }
}
