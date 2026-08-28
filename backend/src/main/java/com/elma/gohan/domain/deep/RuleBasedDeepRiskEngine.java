package com.elma.gohan.domain.deep;

import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.domain.risk.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 按公开线索方向和覆盖率计算有限风险调整。 */
@Component
public class RuleBasedDeepRiskEngine implements DeepRiskEngine {

    private final DeepEvidenceProperties deepProperties;
    private final RiskProperties riskProperties;

    public RuleBasedDeepRiskEngine(DeepEvidenceProperties deepProperties,
                                   RiskProperties riskProperties) {
        this.deepProperties = deepProperties;
        this.riskProperties = riskProperties;
    }

    @Override
    public DeepRiskResult evaluate(BaseRiskSnapshot baseRisk, DeepSignalAnalysis analysis) {
        boolean hasSignals = analysis.sources().values().stream()
                .anyMatch(value -> value.positiveCount() + value.negativeCount()
                        + value.operationalCount() > 0);
        int adjustment = hasSignals
                ? clampAdjustment((int) Math.round(10.0 * analysis.globalBalance()
                * analysis.coverage())) : 0;
        int score = Math.max(0, Math.min(100, baseRisk.riskScore() + adjustment));
        if (baseRisk.riskLevel() != RiskLevel.HIGH) score = Math.min(60, score);

        double confidence = hasSignals
                ? round(0.75 * baseRisk.confidence() + 0.25 * analysis.webConfidence())
                : baseRisk.confidence();
        List<String> reasons = new ArrayList<>();
        if (!hasSignals) {
            reasons.add("公开结果不足，保持基础判断");
        } else {
            reasons.addAll(analysis.negative());
            reasons.addAll(analysis.cautions());
            reasons.addAll(analysis.positive());
            reasons.add(analysis.consistencyReason());
        }
        if (reasons.isEmpty()) reasons.add("公开结果未形成明确风险线索");
        if (reasons.size() > 5) reasons = reasons.subList(0, 5);
        return new DeepRiskResult(score, levelOf(score), confidence, reasons,
                deepProperties.getRiskAlgorithmVersion());
    }

    private int clampAdjustment(int value) {
        return Math.max(-10, Math.min(10, value));
    }

    private RiskLevel levelOf(int score) {
        RiskProperties.Levels levels = riskProperties.getLevels();
        if (score <= levels.getLowMaxInclusive()) return RiskLevel.LOW;
        if (score <= levels.getMediumLowMaxInclusive()) return RiskLevel.MEDIUM_LOW;
        if (score <= levels.getMediumMaxInclusive()) return RiskLevel.MEDIUM;
        return RiskLevel.HIGH;
    }

    private double round(double value) {
        return Math.round(Math.max(0.0, Math.min(1.0, value)) * 1000.0) / 1000.0;
    }
}
