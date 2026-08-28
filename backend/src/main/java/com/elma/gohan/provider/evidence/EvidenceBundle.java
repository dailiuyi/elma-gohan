package com.elma.gohan.provider.evidence;

/** 风险计算所需的评论、平台和一致性 Evidence 集合。 */
public record EvidenceBundle(
        RestaurantEvidence reviewEvidence,
        PlatformEvidence amap,
        PlatformEvidence baidu,
        EntityMatchResult entityMatch,
        CrossPlatformConsistency consistency
) {
    public EvidenceBundle {
        reviewEvidence = reviewEvidence == null ? RestaurantEvidence.empty() : reviewEvidence;
        entityMatch = entityMatch == null ? EntityMatchResult.noMatch() : entityMatch;
        consistency = consistency == null
                ? CrossPlatformConsistency.unknown("跨平台一致性暂不可判断") : consistency;
    }

    public EvidenceSummary summary() {
        return new EvidenceSummary(entityMatch.status(), entityMatch.confidence(),
                consistency.level(), consistency.ratingDifference(), consistency.reason(),
                EvidenceSummary.SourceSummary.from(amap),
                EvidenceSummary.SourceSummary.from(baidu));
    }
}
