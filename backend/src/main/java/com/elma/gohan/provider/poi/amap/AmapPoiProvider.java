package com.elma.gohan.provider.poi.amap;

import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.provider.poi.PoiProvider;
import com.elma.gohan.provider.poi.PoiRecallCache;
import com.elma.gohan.provider.poi.PoiSearchDiagnostics;
import com.elma.gohan.provider.poi.PoiSearchResult;
import org.springframework.stereotype.Component;

/** 调用高德周边检索并标准化餐饮 POI。 */
@Component
public class AmapPoiProvider implements PoiProvider {

    private final AmapClient amapClient;
    private final AmapResponseMapper mapper;
    private final PoiRecallCache recallCache;

    public AmapPoiProvider(AmapClient amapClient, AmapResponseMapper mapper,
                           PoiRecallCache recallCache) {
        this.amapClient = amapClient;
        this.mapper = mapper;
        this.recallCache = recallCache;
    }

    @Override
    public PoiSearchResult nearby(Location location, SearchCondition condition) {
        return recallCache.getOrLoad(location, condition, () -> {
            AmapSearchResult raw = amapClient.searchAround(location.latitude(),
                    location.longitude(), condition);
            var restaurants = mapper.toRestaurants(raw.pois());
            var diagnostics = new PoiSearchDiagnostics(raw.providerTotalCount(),
                    raw.fetchedCount(), raw.pagesFetched(), raw.pois().size(),
                    restaurants.size(), raw.truncated(), raw.lastFetchedDistanceMeters(),
                    raw.queryTypes(), raw.queryKeyword(), raw.completionReason(),
                    raw.retryCount(), null, 0);
            return new PoiSearchResult(restaurants, diagnostics);
        });
    }
}
