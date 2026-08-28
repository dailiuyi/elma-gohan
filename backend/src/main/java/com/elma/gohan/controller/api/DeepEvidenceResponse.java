package com.elma.gohan.controller.api;

import java.util.List;

/** 按需深挖的风险、来源覆盖和公开链接响应。 */
public record DeepEvidenceResponse(
        String recommendationId,
        String restaurantId,
        String restaurantName,
        RiskAssessment baseRisk,
        RiskAssessment deepRisk,
        EvidenceSummaryResponse structuredEvidence,
        List<SourceCoverage> sourceCoverage,
        SignalSummary signals,
        Consistency consistency,
        List<EvidenceLink> links,
        String cacheStatus,
        String generatedAt,
        String expiresAt
) {
    public record SourceCoverage(String source, String status, Integer resultCount) { }
    public record SignalSummary(List<String> positive, List<String> negative,
                                List<String> cautions) { }
    public record Consistency(String level, String reason) { }
    public record EvidenceLink(String source, String title, String url,
                               String publishedAt) { }
}
