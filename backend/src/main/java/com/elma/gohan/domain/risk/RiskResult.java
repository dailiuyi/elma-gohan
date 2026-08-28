package com.elma.gohan.domain.risk;

import com.elma.gohan.provider.evidence.EvidenceSummary;
import java.util.List;

/** 餐厅客观风险、置信度、因子和解释。 */
public record RiskResult(
        int riskScore,
        RiskLevel riskLevel,
        double confidence,
        RiskFactors factors,
        List<String> reasons,
        String algorithmVersion,
        EvidenceSummary evidenceSummary
) {
    public RiskResult {
        riskScore = Math.max(0, Math.min(100, riskScore));
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        factors = factors == null ? RiskFactors.empty() : factors;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public RiskResult(int riskScore, RiskLevel riskLevel, List<String> reasons,
                      String algorithmVersion) {
        this(riskScore, riskLevel, 1.0, RiskFactors.empty(), reasons, algorithmVersion, null);
    }

    public RiskResult(int riskScore, RiskLevel riskLevel, double confidence, RiskFactors factors,
                      List<String> reasons, String algorithmVersion) {
        this(riskScore, riskLevel, confidence, factors, reasons, algorithmVersion, null);
    }
}
