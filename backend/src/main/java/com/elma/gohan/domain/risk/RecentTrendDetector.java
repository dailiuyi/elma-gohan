package com.elma.gohan.domain.risk;

import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.util.List;

public interface RecentTrendDetector {
    TrendResult detect(List<ReviewEvidence> reviews);
}
