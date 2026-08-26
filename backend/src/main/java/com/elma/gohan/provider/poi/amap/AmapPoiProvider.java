package com.elma.gohan.provider.poi.amap;

import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.provider.poi.PoiProvider;
import com.elma.gohan.provider.poi.PoiSearchDiagnostics;
import com.elma.gohan.provider.poi.PoiSearchResult;
import org.springframework.stereotype.Component;

/** 调用高德周边检索并标准化餐饮 POI。 */
@Component
public class AmapPoiProvider implements PoiProvider {

    private final AmapClient amapClient;
    private final AmapResponseMapper mapper;

    public AmapPoiProvider(AmapClient amapClient, AmapResponseMapper mapper) {
        this.amapClient = amapClient;
        this.mapper = mapper;
    }

    @Override
    public PoiSearchResult nearby(Location location, SearchCondition condition) {
        AmapSearchResult raw = amapClient.searchAround(location.latitude(), location.longitude(),
                condition);
        var restaurants = mapper.toRestaurants(raw.pois());
        var diagnostics = new PoiSearchDiagnostics(raw.providerTotalCount(), raw.fetchedCount(),
                raw.pagesFetched(), raw.pois().size(), restaurants.size(), raw.truncated(),
                raw.lastFetchedDistanceMeters(), raw.queryTypes(), raw.queryKeyword());
        return new PoiSearchResult(restaurants, diagnostics);
    }
}
