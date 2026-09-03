package com.elma.gohan.provider.evidence;

import com.elma.gohan.domain.restaurant.Location;
import java.util.List;

/** 批量平台 Evidence 扩展点；单次推荐不得退化为逐餐厅远程调用。 */
public interface PlatformEvidenceProvider {
    PlatformSearchResult searchV3(Location center, int radiusMeters, int pageNumber);

    default PlatformSearchResult searchV3(Location center, int radiusMeters, int pageNumber,
                                          String query) {
        return searchV3(center, radiusMeters, pageNumber);
    }

    default PlatformSearchResult searchNearby(Location center, int radiusMeters, String query) {
        return searchV3(center, radiusMeters, 0, query);
    }

    default PlatformSearchResult searchRegion(String query, String region) {
        return new PlatformSearchResult(EvidenceStatus.NO_DATA, List.of(), 0, 0, 0);
    }

    /** 地点输入提示；默认视为未实现，不记为平台故障。 */
    default PlatformSearchResult searchSuggestion(Location center, String query, String region) {
        return new PlatformSearchResult(EvidenceStatus.NO_DATA, List.of(), 0, 0, 0);
    }

    PlatformSearchResult searchV2(Location center, int radiusMeters);
}
