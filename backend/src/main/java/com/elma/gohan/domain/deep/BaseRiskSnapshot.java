package com.elma.gohan.domain.deep;

import com.elma.gohan.domain.risk.RiskLevel;
import java.util.List;

/** 推荐会话冻结的基础风险快照。 */
public record BaseRiskSnapshot(
        int riskScore,
        RiskLevel riskLevel,
        double confidence,
        List<String> reasons,
        String algorithmVersion
) {
    public BaseRiskSnapshot {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
