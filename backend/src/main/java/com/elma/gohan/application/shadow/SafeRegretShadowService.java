package com.elma.gohan.application.shadow;

import com.elma.gohan.config.SafeRegretProperties;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotEntity;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Shadow 开关与幂等持久化；计算由无 IO 的 calculator 完成。 */
@Service
public class SafeRegretShadowService {
    private final SafeRegretProperties properties;
    private final SafeRegretShadowSnapshotWriter snapshotWriter;
    private final SafeRegretShadowCalculator calculator;

    public SafeRegretShadowService(SafeRegretProperties properties,
            SafeRegretShadowSnapshotWriter snapshotWriter, SafeRegretShadowCalculator calculator) {
        this.properties = properties;
        this.snapshotWriter = snapshotWriter;
        this.calculator = calculator;
    }

    public Optional<RecommendationDecisionSnapshotEntity> capture(SafeRegretShadowInput input) {
        if (!properties.isShadowEnabled()) return Optional.empty();
        var existing = snapshotWriter.findExisting(input.recommendationLogId(), properties.getExperimentKey());
        if (existing.isPresent()) return existing;
        return Optional.of(snapshotWriter.save(calculator.calculate(input)));
    }
}
