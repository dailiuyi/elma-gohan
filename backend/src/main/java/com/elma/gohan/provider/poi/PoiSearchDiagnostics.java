package com.elma.gohan.provider.poi;

/**
 * 一次 POI 召回的完整性诊断；用于服务端判定结果是否已穷尽，不暴露给客户端。
 */
public record PoiSearchDiagnostics(
        Integer providerTotalCount,
        int fetchedCount,
        int pagesFetched,
        int deduplicatedCount,
        int mappedCount,
        boolean truncated,
        Integer lastFetchedDistanceMeters,
        String queryTypes,
        String queryKeyword,
        PoiSearchCompletionReason completionReason,
        int retryCount,
        PoiSearchCacheStatus cacheStatus,
        int estimatedCacheWeightBytes
) {
    /** 兼容既有测试夹具；旧诊断按结果已穷尽、未经过缓存处理解释。 */
    public PoiSearchDiagnostics(Integer providerTotalCount, int fetchedCount, int pagesFetched,
                                int deduplicatedCount, int mappedCount, boolean truncated,
                                Integer lastFetchedDistanceMeters, String queryTypes,
                                String queryKeyword) {
        this(providerTotalCount, fetchedCount, pagesFetched, deduplicatedCount, mappedCount,
                truncated, lastFetchedDistanceMeters, queryTypes, queryKeyword,
                truncated ? PoiSearchCompletionReason.MAX_PAGES_REACHED
                        : PoiSearchCompletionReason.RESULTS_EXHAUSTED,
                0, PoiSearchCacheStatus.MISS, 0);
    }

    public boolean incomplete() {
        return truncated || (completionReason != null && completionReason.incomplete());
    }

    public PoiSearchDiagnostics withCache(PoiSearchCacheStatus status, int weightBytes) {
        return new PoiSearchDiagnostics(providerTotalCount, fetchedCount, pagesFetched,
                deduplicatedCount, mappedCount, truncated, lastFetchedDistanceMeters,
                queryTypes, queryKeyword, completionReason, retryCount, status, weightBytes);
    }
}
