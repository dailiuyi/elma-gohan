package com.elma.gohan.provider.evidence;

import java.util.List;

/** 一页平台地点检索结果及分页元数据。 */
public record PlatformSearchResult(
        EvidenceStatus status,
        List<PlatformEvidence> evidence,
        Integer total,
        int pageNumber,
        int pageSize
) {
    public PlatformSearchResult {
        status = status == null ? EvidenceStatus.UNAVAILABLE : status;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        pageNumber = Math.max(0, pageNumber);
        pageSize = Math.max(0, pageSize);
    }

    public PlatformSearchResult(EvidenceStatus status, List<PlatformEvidence> evidence) {
        this(status, evidence, null, 0, evidence == null ? 0 : evidence.size());
    }

    public static PlatformSearchResult unavailable() {
        return new PlatformSearchResult(EvidenceStatus.UNAVAILABLE, List.of(), null, 0, 0);
    }
}
