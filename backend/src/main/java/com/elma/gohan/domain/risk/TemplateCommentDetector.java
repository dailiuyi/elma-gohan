package com.elma.gohan.domain.risk;

import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.util.List;

/** 计算高度相似评论所占比例。 */
public interface TemplateCommentDetector {
    TemplateDetectionResult detect(List<ReviewEvidence> reviews);
}
