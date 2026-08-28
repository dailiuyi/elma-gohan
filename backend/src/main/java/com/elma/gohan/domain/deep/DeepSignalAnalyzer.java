package com.elma.gohan.domain.deep;

import com.elma.gohan.provider.deep.DeepEvidenceBatch;
import com.elma.gohan.provider.deep.DeepEvidenceSource;
import java.time.Instant;
import java.util.Map;

/** 将标准化 Web Evidence 转换为可解释信号。 */
public interface DeepSignalAnalyzer {
    DeepSignalAnalysis analyze(Map<DeepEvidenceSource, DeepEvidenceBatch> evidence, Instant now);
}
