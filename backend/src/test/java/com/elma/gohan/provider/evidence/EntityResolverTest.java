package com.elma.gohan.provider.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.EntityResolutionProperties;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class EntityResolverTest {

    private final EntityResolver resolver = new EntityResolver(new EntityResolutionProperties());

    @Test
    void normalizesStoreSuffixAndMatchesNearbySameAddress() {
        Restaurant restaurant = TestRestaurants.full("a1", "湘味小馆（旗舰店）", 4.6, 50, 45);
        PlatformEvidence baidu = evidence("b1", "湘味小馆", "麓山南路 1 号",
                28.2291, 112.9412, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.70);
        assertThat(result.evidence().providerPoiId()).isEqualTo("b1");
    }

    @Test
    void renormalizesWeightsWhenAddressAndTelephoneAreUnavailable() {
        Restaurant restaurant = new Restaurant(null, "AMAP", "a1", "湘味小馆",
                28.2291, 112.9412, 50, "CHINESE", "中餐厅", 4.6, 100, 45,
                BusinessStatus.UNKNOWN, "09:00-21:00", null, null,
                DataCompleteness.PARTIAL);
        PlatformEvidence baidu = evidence("b1", "湘味小馆", null,
                28.2292, 112.9412, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.70);
        assertThat(result.features()).containsEntry("availableWeight", 0.75);
    }

    @Test
    void sparseSameNameWithinTwoHundredMetersStillMatches() {
        Restaurant restaurant = new Restaurant(null, "AMAP", "a1", "湘味小馆",
                28.2291, 112.9412, 50, "CHINESE", "中餐厅", 4.6, 100, 45,
                BusinessStatus.UNKNOWN, "09:00-21:00", null, null,
                DataCompleteness.PARTIAL);
        PlatformEvidence baidu = evidence("b1", "湘味小馆", null,
                28.23045, 112.9412, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
    }

    @Test
    void rejectedBestCandidateKeepsDiagnosticFeatures() {
        Restaurant restaurant = new Restaurant(null, "AMAP", "a1", "湘味小馆",
                28.2291, 112.9412, 50, "CHINESE", "中餐厅", 4.6, 100, 45,
                BusinessStatus.UNKNOWN, "09:00-21:00", "麓山南路 1 号",
                "0731-12345678", DataCompleteness.FULL);
        PlatformEvidence baidu = evidence("b1", "湘味小馆", "完全不同的地址",
                28.2350, 112.9412, "0731-87654321");

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.NO_MATCH);
    }

    @Test
    void closeDuplicateCandidatesTakeBestMatch() {
        Restaurant restaurant = TestRestaurants.full("a1", "湘味小馆", 4.6, 50, 45);
        PlatformEvidence first = evidence("b1", "湘味小馆", "麓山南路 1 号",
                28.2291, 112.9412, null);
        PlatformEvidence second = evidence("b2", "湘味小馆", "麓山南路 1 号",
                28.2291, 112.9412, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant),
                List.of(first, second), Set.of()).get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.evidence().providerPoiId()).isEqualTo("b1");
    }

    @Test
    void sameBaiduUidCannotBindTwoAmapRestaurants() {
        Restaurant first = TestRestaurants.full("a1", "湘味小馆", 4.6, 50, 45);
        Restaurant second = TestRestaurants.full("a2", "湘味小馆", 4.6, 55, 45);
        PlatformEvidence baidu = evidence("b1", "湘味小馆", "麓山南路 1 号",
                28.2291, 112.9412, null);

        Map<String, EntityMatchResult> result = resolver.resolve(List.of(first, second),
                List.of(baidu), Set.of());

        assertThat(result.values()).filteredOn(value -> value.status() == EntityMatchStatus.MATCHED)
                .hasSize(1);
        assertThat(result.values()).filteredOn(value -> value.status() == EntityMatchStatus.NO_MATCH)
                .hasSize(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void uidConflictLogsNoMatchAndAcceptableSecondCandidate(CapturedOutput output) {
        Restaurant first = TestRestaurants.full("a1", "湘味小馆", 4.6, 50, 45);
        Restaurant second = TestRestaurants.full("a2", "湘味小馆", 4.6, 55, 45);
        PlatformEvidence best = evidence("b1", "湘味小馆", "麓山南路 1 号",
                28.2291, 112.9412, null);
        PlatformEvidence acceptableSecond = evidence("b2", "湘味小馆", "麓山南路 1 号",
                28.2291, 112.9412, null);

        Map<String, EntityMatchResult> result = resolver.resolve(List.of(first, second),
                List.of(best, acceptableSecond), Set.of());

        assertThat(result.values()).filteredOn(value -> value.status() == EntityMatchStatus.MATCHED)
                .hasSize(1);
        assertThat(result.values()).filteredOn(value -> value.status() == EntityMatchStatus.NO_MATCH)
                .hasSize(1);
        assertThat(output).contains("conflicts=1", "conflictNoMatch=1",
                "acceptableSecondCandidate=true", "acceptableSecondCandidateCount=1");
    }

    @Test
    void exactNameWithinEightyMetersMatchesEvenWhenAddressAndPhoneDiffer() {
        Restaurant restaurant = new Restaurant(null, "AMAP", "a1", "八方1980私宴",
                28.2291, 112.9412, 50, "CHINESE", "中餐厅", 4.6, 100, 45,
                BusinessStatus.UNKNOWN, "09:00-21:00", "麓谷大道627号",
                "0731-11111111", DataCompleteness.FULL);
        PlatformEvidence baidu = evidence("b1", "八方1980私宴", "完全不同的地址写法",
                28.22914, 112.9412, "0731-22222222");

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.70);
    }

    @Test
    void parentheticalBranchNamesStillMatchSameBrandNearby() {
        Restaurant restaurant = TestRestaurants.full("a1", "天天见面(麓谷·新长海中心店)", 4.6, 50, 45);
        PlatformEvidence baidu = evidence("b1", "天天见面(新长海中心店)", "麓谷新长海中心",
                28.2292, 112.9412, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.features()).containsEntry("name", 1.0);
    }

    @Test
    void sameBrandDifferentVenueSuffixMatches() {
        Restaurant restaurant = TestRestaurants.full("a1", "新食尚餐厅(谷虹路店)", 4.6, 40, 40);
        PlatformEvidence baidu = evidence("b1", "新食尚原味家菜馆", "麓谷大道627号麓谷·新长海中心",
                28.2292, 112.9413, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(EntityResolver.searchQueries("新食尚餐厅(谷虹路店)", "麓谷大道627号"))
                .containsExactly("新食尚餐厅谷虹路", "新食尚");
    }

    @Test
    void renamedBrandAtSameDoorwayMatches() {
        Restaurant restaurant = TestRestaurants.full("a1", "地道伙夫湘菜馆", 4.6, 20, 40);
        PlatformEvidence baidu = evidence("b1", "地摊伙夫必吃餐厅", "文轩路麓谷锦和园",
                28.22912, 112.94121, null);

        assertThat(resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.MATCHED);
    }

    @Test
    void restaurantMatchesCompanyRegistrationAtSameAddress() {
        Restaurant restaurant = new Restaurant(null, "AMAP", "a1", "悦味餐厅",
                28.208604, 112.886308, 80, "CHINESE", "中餐厅", 4.2, 20, 30,
                BusinessStatus.UNKNOWN, null, "文轩路27号麓谷钰园A4栋106房",
                "0731-84864855", DataCompleteness.PARTIAL);
        PlatformEvidence baidu = evidence("b1", "长沙悦味餐饮管理有限公司",
                "岳麓区高新区文轩路27号麓谷企业广场A4栋106",
                28.208432, 112.884957, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(EntityResolver.searchQueries("悦味餐厅", "文轩路27号麓谷钰园A4栋106房"))
                .containsExactly("悦味餐厅", "悦味");
    }

    @Test
    void similarBrandNamesAtSameMallMatchWithoutPhone() {
        Restaurant restaurant = TestRestaurants.full("a1", "锋记·港式烧味", 4.6, 50, 45);
        PlatformEvidence baidu = evidence("b1", "锋记·港式烧腊", "麓谷大道627号麓谷·新长海中心裙楼103-1",
                28.2295, 112.9413, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
    }

    @Test
    void closeTelephoneMatchDoesNotRequireHighNameJaccard() {
        Restaurant restaurant = new Restaurant(null, "AMAP", "a1", "湘春酒家(IFS国金中心店)",
                28.2291, 112.9412, 50, "CHINESE", "中餐厅", 4.6, 100, 45,
                BusinessStatus.UNKNOWN, "09:00-21:00", "IFS国金中心",
                "0731-12345678", DataCompleteness.FULL);
        PlatformEvidence baidu = evidence("b1", "湘春酒家IFS店", "长沙IFS",
                28.22916, 112.9412, "0731-12345678");

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");

        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
    }

    @Test
    void kaixiangxiangPrefixedBrandAtSameDoorwayMatches() {
        Restaurant restaurant = TestRestaurants.full("a1", "开坛湘·坛子菜·钵子饭(麓云店)", 4.6, 20, 40);
        PlatformEvidence baidu = evidence("b1", "菜道味·开坛湘·坛子菜·钵子饭(麓云店)",
                "岳麓区-麓云路128号永安村综合楼101房", 28.22914, 112.94121, null);

        assertThat(resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(EntityResolver.searchQueries("开坛湘·坛子菜·钵子饭(麓云店)", "麓云路124号"))
                .containsExactly("开坛湘坛子菜钵子饭麓云", "开坛湘");
    }

    @Test
    void renamedWendlaosanAtSameDoorwayMatches() {
        Restaurant restaurant = TestRestaurants.full("a1", "文老三跳跳蛙活鱼馆", 4.6, 20, 40);
        PlatformEvidence baidu = evidence("b1", "文老三家常菜3毛烧烤",
                "岳麓区-八家湾小区永安片3栋4号", 28.22914, 112.94121, null);

        assertThat(resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(EntityResolver.searchQueries("文老三跳跳蛙活鱼馆", null))
                .containsExactly("文老三跳跳蛙活鱼馆", "文老三");
    }

    @Test
    void latinBrandPizzaMatchesNearbyBranch() {
        Restaurant restaurant = TestRestaurants.full("a1", "S-pizza披萨速递(梅溪湖店)", 4.6, 20, 40);
        PlatformEvidence baidu = evidence("b1", "S-pizza披萨速递(梅溪湖店)",
                "岳麓区-麓谷街道麓泉社区枫林三路398号麓谷明珠家园7栋-107号",
                28.22912, 112.94120, null);

        EntityMatchResult result = resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1");
        assertThat(result.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(EntityResolver.searchQueries("S-pizza披萨速递(梅溪湖店)", null))
                .containsExactly("spizza披萨速递梅溪湖", "spizza");
    }

    @Test
    void luojiOldYiyangKeepsBrandAfterHotpotSuffix() {
        Restaurant restaurant = TestRestaurants.full("a1", "罗记老益阳围炉麻辣烫", 4.6, 20, 40);
        PlatformEvidence baidu = evidence("b1", "罗记老益阳围炉麻辣烫",
                "岳麓区-咸嘉湖西路与麓云路交叉口西150米", 28.22912, 112.94120, null);

        assertThat(resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(EntityResolver.searchQueries("罗记老益阳围炉麻辣烫", null))
                .containsExactly("罗记老益阳围炉麻辣烫", "罗记老益阳");
    }

    @Test
    void searchQueriesRejectBlankAndSingleCharacterTerms() {
        assertThat(EntityResolver.searchQueries(null, null)).isEmpty();
        assertThat(EntityResolver.searchQueries("店", null)).isEmpty();
        assertThat(EntityResolver.searchQueries("A", null)).isEmpty();
    }

    @Test
    void didaohuofuXiangtanCaiAtSameAddressMatches() {
        Restaurant restaurant = TestRestaurants.full("a1", "地道伙夫湘菜馆", 4.6, 20, 40);
        PlatformEvidence baidu = evidence("b1", "地道伙夫湘潭菜(涉外店)", "文轩路120号",
                28.22910, 112.94120, null);

        assertThat(resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.MATCHED);
    }

    @Test
    void mallNeighborWithDifferentBrandIsRejected() {
        Restaurant restaurant = TestRestaurants.full("a1", "新食尚餐厅(谷虹路店)", 4.6, 40, 40);
        PlatformEvidence baidu = evidence("b1", "蒸食尚(麓谷·新长海中心店)",
                "麓谷大道627号海创科技工业园A1栋103", 28.2299, 112.9415, null);

        assertThat(resolver.resolve(List.of(restaurant), List.of(baidu), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.NO_MATCH);
    }

    @Test
    void farPoiIsRejectedUnlessTelephoneAndNameAgree() {
        Restaurant restaurant = new Restaurant(null, "AMAP", "a1", "湘味小馆",
                28.2291, 112.9412, 50, "CHINESE", "中餐厅", 4.6, 100, 45,
                BusinessStatus.UNKNOWN, "09:00-21:00", "麓山南路 1 号",
                "0731-12345678", DataCompleteness.FULL);
        PlatformEvidence withoutPhone = evidence("b1", "湘味小馆", "其他地址",
                28.2391, 112.9512, null);
        PlatformEvidence withPhone = evidence("b2", "湘味小馆", "其他地址",
                28.2391, 112.9512, "0731 12345678");

        assertThat(resolver.resolve(List.of(restaurant), List.of(withoutPhone), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.NO_MATCH);
        assertThat(resolver.resolve(List.of(restaurant), List.of(withPhone), Set.of())
                .get("a1").status()).isEqualTo(EntityMatchStatus.MATCHED);
    }

    private static PlatformEvidence evidence(String id, String name, String address,
                                              double latitude, double longitude,
                                              String telephone) {
        return new PlatformEvidence("BAIDU", id, EvidenceStatus.AVAILABLE, null,
                name, address, latitude, longitude, 4.5, null, null, null,
                100, 45, "09:00-21:00", null, telephone);
    }
}
