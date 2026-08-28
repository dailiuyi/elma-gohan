package com.elma.gohan.domain.deep;

import com.elma.gohan.domain.risk.RiskLevel;
import java.util.List;

/** 深挖后的风险分、置信度和解释。 */
public record DeepRiskResult(
        int riskScore,
        RiskLevel riskLevel,
        double confidence,
        List<String> reasons,
        String algorithmVersion
) {
    public DeepRiskResult {
        riskScore = Math.max(0, Math.min(100, riskScore));
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
