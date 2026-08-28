package com.elma.gohan.provider.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PoiRecallCacheTest {

    @Test
    void budgetAndDislikesDoNotSplitTheRecallCacheKey() {
        PoiRecallCache cache = new PoiRecallCache(properties());
        AtomicInteger loads = new AtomicInteger();
        Location location = new Location(28.20001, 112.90001);

        PoiSearchResult first = cache.getOrLoad(location,
                condition(20, 40, List.of()), () -> result(loads.incrementAndGet()));
        PoiSearchResult second = cache.getOrLoad(location,
                condition(40, 60, List.of("香菜")), () -> result(loads.incrementAndGet()));
        PoiSearchResult third = cache.getOrLoad(location,
                condition(60, 80, List.of("辣椒")), () -> result(loads.incrementAndGet()));
        PoiSearchResult fourth = cache.getOrLoad(location,
                condition(80, 120, List.of()), () -> result(loads.incrementAndGet()));

        assertThat(loads).hasValue(1);
        assertThat(first.diagnostics().cacheStatus()).isEqualTo(PoiSearchCacheStatus.MISS);
        assertThat(second.diagnostics().cacheStatus()).isEqualTo(PoiSearchCacheStatus.HIT);
        assertThat(third.diagnostics().cacheStatus()).isEqualTo(PoiSearchCacheStatus.HIT);
        assertThat(fourth.diagnostics().cacheStatus()).isEqualTo(PoiSearchCacheStatus.HIT);
    }

    @Test
    void categoryRingAndCoordinateGridDoSplitTheKey() {
        PoiRecallCache cache = new PoiRecallCache(properties());
        AtomicInteger loads = new AtomicInteger();
        Location location = new Location(28.20001, 112.90001);

        cache.getOrLoad(location, condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));
        cache.getOrLoad(location, new SearchCondition(1000, 2000, 20, 40, "ANY", List.of()),
                () -> result(loads.incrementAndGet()));
        cache.getOrLoad(location, new SearchCondition(500, 1000, 20, 40, "HOT_POT", List.of()),
                () -> result(loads.incrementAndGet()));
        cache.getOrLoad(new Location(28.2002, 112.90001), condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));

        assertThat(loads).hasValue(4);
    }

    @Test
    void categoryMappingAndCacheStructureVersionsSplitTheKey() {
        AmapProperties properties = properties();
        PoiRecallCache cache = new PoiRecallCache(properties);
        AtomicInteger loads = new AtomicInteger();
        Location location = new Location(28.20001, 112.90001);

        cache.getOrLoad(location, condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));
        properties.setCategoryMappingVersion("category-map-v2");
        cache.getOrLoad(location, condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));
        properties.setCacheVersion("recall-v2");
        cache.getOrLoad(location, condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));

        assertThat(loads).hasValue(3);
    }

    @Test
    void concurrentMissesAreCoalescedIntoOneLoad() throws Exception {
        PoiRecallCache cache = new PoiRecallCache(properties());
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PoiSearchResult> owner = executor.submit(() -> cache.getOrLoad(
                    new Location(28.2, 112.9), condition(20, 40, List.of()), () -> {
                        loads.incrementAndGet();
                        loading.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return result(1);
                    }));
            assertThat(loading.await(1, TimeUnit.SECONDS)).isTrue();
            Future<PoiSearchResult> follower = executor.submit(() -> cache.getOrLoad(
                    new Location(28.2, 112.9), condition(80, 120, List.of()),
                    () -> result(loads.incrementAndGet())));
            Thread.sleep(50);
            release.countDown();

            assertThat(owner.get(2, TimeUnit.SECONDS).diagnostics().cacheStatus())
                    .isEqualTo(PoiSearchCacheStatus.MISS);
            assertThat(follower.get(2, TimeUnit.SECONDS).diagnostics().cacheStatus())
                    .isEqualTo(PoiSearchCacheStatus.COALESCED);
            assertThat(loads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiresAndEvictsByConfiguredLogicalWeight() throws Exception {
        AmapProperties properties = properties();
        properties.setCacheTtlSeconds(1);
        properties.setCacheMaxWeightBytes(1024L * 1024L);
        properties.setCacheMinEntryWeightBytes(512 * 1024);
        PoiRecallCache cache = new PoiRecallCache(properties);
        AtomicInteger loads = new AtomicInteger();

        cache.getOrLoad(new Location(28.2, 112.9), condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));
        Thread.sleep(1100);
        cache.getOrLoad(new Location(28.2, 112.9), condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));
        assertThat(loads).hasValue(2);

        cache.getOrLoad(new Location(28.2002, 112.9), condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));
        cache.getOrLoad(new Location(28.2004, 112.9), condition(20, 40, List.of()),
                () -> result(loads.incrementAndGet()));
        cache.cleanUp();
        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(2);
    }

    @Test
    void cacheHitRecomputesDistanceFromTheActualCoordinate() {
        PoiRecallCache cache = new PoiRecallCache(properties());
        Location firstLocation = new Location(28.20001, 112.90001);
        Location secondLocation = new Location(28.20004, 112.90004);

        PoiSearchResult first = cache.getOrLoad(firstLocation, condition(20, 40, List.of()),
                () -> result(1));
        PoiSearchResult second = cache.getOrLoad(secondLocation, condition(20, 40, List.of()),
                () -> result(2));

        int expected = PoiRecallCache.distanceMeters(secondLocation.latitude(),
                secondLocation.longitude(), 28.201, 112.901);
        assertThat(second.diagnostics().cacheStatus()).isEqualTo(PoiSearchCacheStatus.HIT);
        assertThat(second.restaurants().get(0).distanceMeters()).isEqualTo(expected);
        assertThat(second.restaurants().get(0).distanceMeters())
                .isNotEqualTo(first.restaurants().get(0).distanceMeters());
    }

    private static AmapProperties properties() {
        AmapProperties properties = new AmapProperties();
        properties.setCacheTtlSeconds(60);
        properties.setCacheMaxWeightBytes(32L * 1024 * 1024);
        properties.setCacheMinEntryWeightBytes(512 * 1024);
        properties.setSearchTypesByCategory(java.util.Map.of(
                "ANY", "050000", "HOT_POT", "050117"));
        return properties;
    }

    private static SearchCondition condition(Integer minBudget, Integer maxBudget,
                                             List<String> dislikes) {
        return new SearchCondition(500, 1000, minBudget, maxBudget, "ANY", dislikes);
    }

    private static PoiSearchResult result(int suffix) {
        Restaurant restaurant = new Restaurant(null, "AMAP", "poi-" + suffix,
                "测试餐厅", 28.201, 112.901, 999, "MEAL", "正餐", 4.2,
                100, 35, BusinessStatus.OPEN, "10:00-22:00", "测试路",
                null, DataCompleteness.FULL);
        PoiSearchDiagnostics diagnostics = new PoiSearchDiagnostics(1, 1, 1, 1, 1,
                false, 999, "050000", null, PoiSearchCompletionReason.RESULTS_EXHAUSTED,
                0, null, 0);
        return new PoiSearchResult(List.of(restaurant), diagnostics);
    }
}
