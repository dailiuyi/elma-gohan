package com.elma.gohan.domain.deep;

/** 在基础风险上有限叠加公开 Web 线索的深挖风险引擎。 */
public interface DeepRiskEngine {
    DeepRiskResult evaluate(BaseRiskSnapshot baseRisk, DeepSignalAnalysis analysis);
}
