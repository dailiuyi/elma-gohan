package com.elma.gohan.domain.risk;

import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.util.List;

/** 根据评论评分和时间判断近期趋势。 */
public interface RecentTrendDetector {
    TrendResult detect(List<ReviewEvidence> reviews);
}
