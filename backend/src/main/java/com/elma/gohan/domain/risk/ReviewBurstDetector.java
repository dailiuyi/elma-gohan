package com.elma.gohan.domain.risk;

import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.util.List;

/** 检测评论数量是否在短窗口内异常集中。 */
public interface ReviewBurstDetector {
    BurstDetectionResult detect(List<ReviewEvidence> reviews);
}
