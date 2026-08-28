package com.elma.gohan.domain.deep;

import com.elma.gohan.provider.deep.DeepEvidenceSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 公开搜索线索的聚合信号分析。 */
public record DeepSignalAnalysis(
        List<String> positive,
        List<String> negative,
        List<String> cautions,
        Map<DeepEvidenceSource, SourceSignalStats> sources,
        double webConfidence,
        String consistencyLevel,
        String consistencyReason,
        double globalBalance,
        double coverage,
        int relevantResultCount,
        int availableSourceCount,
        Instant generatedAt,
        Instant expiresAt,
        String algorithmVersion
) {
    public DeepSignalAnalysis {
        positive = positive == null ? List.of() : List.copyOf(positive);
        negative = negative == null ? List.of() : List.copyOf(negative);
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
        webConfidence = clamp(webConfidence);
        globalBalance = Math.max(-1.0, Math.min(1.0, globalBalance));
        coverage = clamp(coverage);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
