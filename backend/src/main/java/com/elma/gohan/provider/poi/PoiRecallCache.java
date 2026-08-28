package com.elma.gohan.provider.poi;

import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 60 秒有界进程内 POI 召回缓存；预算、忌口、身份和随机种子不参与缓存键。 */
@Component
public class PoiRecallCache {

    private static final Logger log = LoggerFactory.getLogger(PoiRecallCache.class);
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final AmapProperties properties;
    private final Cache<RecallCacheKey, PoiSearchResult> cache;
    private final ConcurrentHashMap<RecallCacheKey, CompletableFuture<PoiSearchResult>> inFlight
            = new ConcurrentHashMap<>();

    public PoiRecallCache(AmapProperties properties) {
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, properties.getCacheTtlSeconds())))
                .maximumWeight(Math.max(1L, properties.getCacheMaxWeightBytes()))
                .weigher((RecallCacheKey key, PoiSearchResult value) -> estimateWeight(value))
                .build();
    }

    public PoiSearchResult getOrLoad(Location actualLocation, SearchCondition condition,
                                     Supplier<PoiSearchResult> loader) {
        RecallCacheKey key = key(actualLocation, condition);
        PoiSearchResult cached = cache.getIfPresent(key);
        if (cached != null) {
            return rebase(cached, actualLocation, PoiSearchCacheStatus.HIT);
        }

        CompletableFuture<PoiSearchResult> loading = new CompletableFuture<>();
        CompletableFuture<PoiSearchResult> existing = inFlight.putIfAbsent(key, loading);
        if (existing != null) {
            try {
                return rebase(existing.join(), actualLocation, PoiSearchCacheStatus.COALESCED);
            } catch (CompletionException e) {
                throw propagate(e.getCause());
            }
        }

        try {
            PoiSearchResult loaded = loader.get();
            int weight = estimateWeight(loaded);
            PoiSearchResult normalized = withCache(loaded, PoiSearchCacheStatus.MISS, weight);
            if (loaded.diagnostics() != null && loaded.diagnostics().pagesFetched() > 0) {
                cache.put(key, normalized);
            }
            loading.complete(normalized);
            return rebase(normalized, actualLocation, PoiSearchCacheStatus.MISS);
        } catch (Throwable throwable) {
            loading.completeExceptionally(throwable);
            throw propagate(throwable);
        } finally {
            inFlight.remove(key, loading);
        }
    }

    private PoiSearchResult rebase(PoiSearchResult source, Location actualLocation,
                                   PoiSearchCacheStatus status) {
        List<Restaurant> rebased = new ArrayList<>(source.restaurants().size());
        for (Restaurant restaurant : source.restaurants()) {
            int distance = distanceMeters(actualLocation.latitude(), actualLocation.longitude(),
                    restaurant.latitude(), restaurant.longitude());
            rebased.add(restaurant.withDistance(distance));
        }
        int weight = source.diagnostics() == null
                ? estimateWeight(source) : source.diagnostics().estimatedCacheWeightBytes();
        PoiSearchResult result = new PoiSearchResult(rebased,
                source.diagnostics() == null ? null : source.diagnostics().withCache(status, weight));
        PoiSearchDiagnostics diagnostics = result.diagnostics();
        if (diagnostics != null) {
            log.info("POI召回缓存 traceId={} status={} pages={} retries={} fetched={} mapped={} "
                            + "completionReason={} complete={} estimatedWeightBytes={}",
                    traceId(), status, diagnostics.pagesFetched(), diagnostics.retryCount(),
                    diagnostics.fetchedCount(), diagnostics.mappedCount(),
                    diagnostics.completionReason(), !diagnostics.incomplete(), weight);
        }
        return result;
    }

    private PoiSearchResult withCache(PoiSearchResult result, PoiSearchCacheStatus status,
                                      int weight) {
        return new PoiSearchResult(result.restaurants(), result.diagnostics() == null
                ? null : result.diagnostics().withCache(status, weight));
    }

    private RecallCacheKey key(Location location, SearchCondition condition) {
        return new RecallCacheKey(
                round(location.latitude()),
                round(location.longitude()),
                condition.minDistance(),
                condition.radius(),
                properties.searchTypesFor(condition.category()),
                properties.searchKeywordFor(condition.category()),
                properties.getCategoryMappingVersion(),
                properties.getCacheVersion());
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(Math.max(0, properties.getCacheCoordinateDecimals()), RoundingMode.HALF_UP)
                .doubleValue();
    }

    private int estimateWeight(PoiSearchResult result) {
        long estimate = 1024L;
        for (Restaurant restaurant : result.restaurants()) {
            estimate += 512L;
            estimate += stringBytes(restaurant.name());
            estimate += stringBytes(restaurant.sourcePoiId());
            estimate += stringBytes(restaurant.categoryCode());
            estimate += stringBytes(restaurant.categoryLabel());
            estimate += stringBytes(restaurant.openingHours());
            estimate += stringBytes(restaurant.address());
            estimate += stringBytes(restaurant.telephone());
        }
        estimate = Math.max(estimate, properties.getCacheMinEntryWeightBytes());
        return (int) Math.min(Integer.MAX_VALUE, estimate);
    }

    private static long stringBytes(String value) {
        return value == null ? 0L : 40L + value.length() * 2L;
    }

    static int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double latDelta = Math.toRadians(lat2 - lat1);
        double lonDelta = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDelta / 2) * Math.sin(lonDelta / 2);
        return (int) Math.round(EARTH_RADIUS_METERS * 2
                * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    long estimatedSize() {
        return cache.estimatedSize();
    }

    void cleanUp() {
        cache.cleanUp();
    }

    /** 运维与隔离测试可显式清空；生产正常路径只依赖 TTL 与权重淘汰。 */
    public void invalidateAll() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) return runtimeException;
        if (throwable instanceof Error error) throw error;
        return new IllegalStateException("POI召回加载失败", throwable);
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "-" : value;
    }

    private record RecallCacheKey(double latitudeGrid, double longitudeGrid,
                                  Integer minDistance, int radius, String types,
                                  String keyword, String categoryMappingVersion,
                                  String version) {
    }
}
