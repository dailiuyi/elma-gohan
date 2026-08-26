package com.elma.gohan.provider.poi.amap;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 高德原始候选及分页完整性信息。 */
public record AmapSearchResult(
        List<JsonNode> pois,
        Integer providerTotalCount,
        int fetchedCount,
        int pagesFetched,
        boolean truncated,
        Integer lastFetchedDistanceMeters,
        String queryTypes,
        String queryKeyword
) {
    public AmapSearchResult {
        pois = List.copyOf(pois);
    }
}
