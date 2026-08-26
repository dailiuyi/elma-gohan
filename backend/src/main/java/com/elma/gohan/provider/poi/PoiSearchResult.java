package com.elma.gohan.provider.poi;

import com.elma.gohan.domain.restaurant.Restaurant;
import java.util.List;

/** 标准化餐厅候选及其召回完整性诊断。 */
public record PoiSearchResult(
        List<Restaurant> restaurants,
        PoiSearchDiagnostics diagnostics
) {
    public PoiSearchResult {
        restaurants = List.copyOf(restaurants);
    }
}
