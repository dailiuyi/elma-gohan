package com.elma.gohan.domain.recommendation;

/**
 * 候选池随机抽取前的最小充分快照。
 * source + sourcePoiId 唯一定位候选，diversityKey 与 lowRegretScore 足以重放分组加权抽取。
 */
public record SelectionCandidate(
        String source,
        String sourcePoiId,
        String diversityKey,
        double lowRegretScore,
        boolean topK,
        boolean explorationEligible
) {
    public SelectionCandidate(String source, String sourcePoiId, String diversityKey,
                              double lowRegretScore) {
        this(source, sourcePoiId, diversityKey, lowRegretScore, true, false);
    }

    public String candidateKey() {
        return (source == null ? "" : source) + "\u0000"
                + (sourcePoiId == null ? "" : sourcePoiId);
    }
}
