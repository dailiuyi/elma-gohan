package com.elma.gohan.provider.poi.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.elma.gohan.provider.poi.PoiSearchCompletionReason;
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
        String queryKeyword,
        PoiSearchCompletionReason completionReason,
        int retryCount
) {
    public AmapSearchResult {
        pois = List.copyOf(pois);
    }

    public AmapSearchResult(List<JsonNode> pois, Integer providerTotalCount, int fetchedCount,
                            int pagesFetched, boolean truncated,
                            Integer lastFetchedDistanceMeters, String queryTypes,
                            String queryKeyword) {
        this(pois, providerTotalCount, fetchedCount, pagesFetched, truncated,
                lastFetchedDistanceMeters, queryTypes, queryKeyword,
                truncated ? PoiSearchCompletionReason.MAX_PAGES_REACHED
                        : PoiSearchCompletionReason.RESULTS_EXHAUSTED,
                0);
    }
}
