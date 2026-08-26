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
        String queryKeyword
) {
}
