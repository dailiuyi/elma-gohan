package com.elma.gohan.domain.risk.posterior;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Posterior results plus the batch-level rating calibration snapshot. */
public record RiskPosteriorBatchResult(
        double amapMinusBaiduMedianBias,
        int calibrationPairCount,
        Map<String, RiskPosteriorResult> posteriors,
        Map<String, RiskPosteriorInput> inputs
) {
    public RiskPosteriorBatchResult {
        posteriors = Collections.unmodifiableMap(new LinkedHashMap<>(posteriors));
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        if (!posteriors.keySet().equals(inputs.keySet())) {
            throw new IllegalArgumentException("posterior 与 input 的候选 key 必须一致");
        }
    }
}
