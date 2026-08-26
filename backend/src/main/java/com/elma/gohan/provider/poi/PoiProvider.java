package com.elma.gohan.provider.poi;

import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.SearchCondition;

/**
 * 附近餐厅查询抽象:未来可替换为腾讯等其他数据源而不影响 RiskEngine / RecommendationEngine。
 */
public interface PoiProvider {

    PoiSearchResult nearby(Location location, SearchCondition condition);
}
