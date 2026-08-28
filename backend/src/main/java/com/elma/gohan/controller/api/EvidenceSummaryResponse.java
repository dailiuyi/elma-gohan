package com.elma.gohan.controller.api;

import com.elma.gohan.provider.evidence.EvidenceSummary;

/** 面向客户端的高德、百度 Evidence 摘要。 */
public record EvidenceSummaryResponse(
        String matchStatus,
        Double matchConfidence,
        String consistency,
        Double ratingDifference,
        String reason,
        SourceSummary amap,
        SourceSummary baidu
) {
    public static EvidenceSummaryResponse from(EvidenceSummary summary) {
        if (summary == null) return null;
        return new EvidenceSummaryResponse(summary.matchStatus().name(), summary.matchConfidence(),
                summary.consistency().name(), summary.ratingDifference(), summary.reason(),
                SourceSummary.from(summary.amap()), SourceSummary.from(summary.baidu()));
    }

    public record SourceSummary(
            String status,
            Double rating,
            Double tasteRating,
            Double serviceRating,
            Double environmentRating,
            Integer averagePrice,
            Integer commentCount
    ) {
        private static SourceSummary from(EvidenceSummary.SourceSummary source) {
            if (source == null) return null;
            return new SourceSummary(source.status().name(), source.rating(), source.tasteRating(),
                    source.serviceRating(), source.environmentRating(), source.averagePrice(),
                    source.commentCount());
        }
    }
}
