package com.elma.gohan.provider.deep;

import java.time.Instant;
import java.util.List;

/** 搜索服务返回并完成标准化的单条公开线索。 */
public record WebEvidenceItem(
        DeepEvidenceSource source,
        String title,
        String url,
        String snippet,
        Instant publishedAt,
        Instant fetchedAt,
        double entityMatchConfidence,
        List<String> signalTags
) {
    public WebEvidenceItem {
        signalTags = signalTags == null ? List.of() : List.copyOf(signalTags);
        entityMatchConfidence = Math.max(0.0, Math.min(1.0, entityMatchConfidence));
    }
}
