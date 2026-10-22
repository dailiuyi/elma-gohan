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
    void legacyMatchingDistinguishesExactFuzzyAndUnrelatedNames() {
        DeepEvidenceProperties properties = new DeepEvidenceProperties();
        properties.setImprovedSearchEnabled(false);
        WebEvidenceMatcher legacy = new WebEvidenceMatcher(properties);
        var restaurant = TestRestaurants.full("a", "大学城湘味家常菜馆", 4.6, 500, 42);

        assertThat(legacy.match(restaurant, restaurant.name(), "麓山南路")).isEqualTo(1.0);
        assertThat(legacy.match(restaurant, restaurant.name(), null)).isEqualTo(0.95);
        assertThat(legacy.match(restaurant, "大学城湘味家常菜", "麓山南路"))
                .isBetween(properties.getEntityMatchThreshold(), 0.94);
        assertThat(legacy.match(restaurant, "大学城湘味家常菜", "五一广场")).isZero();
        assertThat(legacy.match(restaurant, null, null)).isZero();
        assertThat(legacy.match(TestRestaurants.full("b", "", 4.0, 100, 30), "探店", null))
                .isZero();
        assertThat(legacy.match(TestRestaurants.full("c", "王", 4.0, 100, 30), "李", "麓山南路"))
                .isZero();
        assertThat(legacy.match(restaurant, null, "麓山南路")).isZero();
    }

    @Test
    void strictMatchingRequiresBranchOrLocationAndRejectsMissingTitle() {
        var restaurant = TestRestaurants.full("a", "湘味家常菜", 4.6, 500, 42);
        assertThat(matcher.match(restaurant, "湘味家常菜探店", "麓山南路"))
                .isEqualTo(0.85);
        assertThat(matcher.match(restaurant, "湘味家常菜探店", null)).isZero();
        assertThat(matcher.match(restaurant, null, "湘味家常菜 麓山南路")).isZero();
        assertThat(matcher.match(TestRestaurants.full("b", "王", 4.0, 100, 30),
                "王 麓山南路", null)).isZero();
    }

    @Test
    void normalizesUnicodeAndRepeatedSuffixesWithoutRemovingShortNames() {
        DeepEvidenceProperties properties = new DeepEvidenceProperties();
        properties.setStoreSuffixes(java.util.List.of("", "店", "旗舰店"));
        WebEvidenceMatcher custom = new WebEvidenceMatcher(properties);

        assertThat(custom.normalizeName("ＡＢＣ旗舰店店")).isEqualTo("abc");
        assertThat(custom.normalizeName("小店")).isEqualTo("小店");
        assertThat(custom.normalizeName(null)).isEmpty();
        assertThat(custom.normalize(" ＡＢＣ·湘菜（店）! ")).isEqualTo("abc湘菜店");
    }

    @Test
    void locationSearchHandlesMissingNumericDuplicateAndLongAddresses() {
        assertThat(matcher.addressKeywords(null)).isEmpty();
        assertThat(matcher.addressKeywords("  ")).isEmpty();
        assertThat(matcher.addressKeywords("123号 / 麓山南路 / 麓山南路 / 大学城 / 中心广场 / 商场"))
                .containsExactly("麓山南路", "大学城", "中心广场");
        assertThat(matcher.searchLocationKeyword("湘菜（分店）", "大学城"))
                .isEqualTo("大学城");
        assertThat(matcher.searchLocationKeyword(null, null)).isEmpty();
        String longAddress = "这是一个长度超过十六个字符的非常长的地址名称";
        assertThat(matcher.addressKeywords(longAddress))
                .containsExactly(longAddress.substring(longAddress.length() - 16));
        assertThat(matcher.suggestedSearch(TestRestaurants.full("a", "湘菜（大学城店）", 4.0, 100, 30)))
                .isEqualTo("湘菜（大学城店） 长沙 大学城");
    }

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
