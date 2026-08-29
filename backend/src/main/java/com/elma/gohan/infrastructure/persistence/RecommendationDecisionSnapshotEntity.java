package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 推荐会话在一个实验下的 served/shadow 全候选决策快照。 */
@Entity
@Table(name = "recommendation_decision_snapshot")
public class RecommendationDecisionSnapshotEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "recommendation_log_id", nullable = false)
    private UUID recommendationLogId;

    @Column(name = "experiment_key", length = 64, nullable = false)
    private String experimentKey;

    @Column(name = "variant", length = 32, nullable = false)
    private String variant;

    @Column(name = "served_risk_algorithm_version", length = 32, nullable = false)
    private String servedRiskAlgorithmVersion;

    @Column(name = "shadow_risk_algorithm_version", length = 32, nullable = false)
    private String shadowRiskAlgorithmVersion;

    @Column(name = "served_recommendation_algorithm_version", length = 32, nullable = false)
    private String servedRecommendationAlgorithmVersion;

    @Column(name = "shadow_recommendation_algorithm_version", length = 32, nullable = false)
    private String shadowRecommendationAlgorithmVersion;

    @Column(name = "random_seed", nullable = false)
    private long randomSeed;

    @Column(name = "selection_propensity")
    private Double selectionPropensity;

    @Column(name = "config_hash", length = 128, nullable = false)
    private String configHash;

    @Column(name = "feature_schema_version", nullable = false)
    private int featureSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "all_candidates_json", columnDefinition = "jsonb", nullable = false)
    private String allCandidatesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RecommendationDecisionSnapshotEntity() {
    }

    public RecommendationDecisionSnapshotEntity(
            UUID id,
            UUID recommendationLogId,
            String experimentKey,
            String variant,
            String servedRiskAlgorithmVersion,
            String shadowRiskAlgorithmVersion,
            String servedRecommendationAlgorithmVersion,
            String shadowRecommendationAlgorithmVersion,
            long randomSeed,
            Double selectionPropensity,
            String configHash,
            int featureSchemaVersion,
            String allCandidatesJson,
            LocalDateTime createdAt) {
        this.id = id;
        this.recommendationLogId = recommendationLogId;
        this.experimentKey = experimentKey;
        this.variant = variant;
        this.servedRiskAlgorithmVersion = servedRiskAlgorithmVersion;
        this.shadowRiskAlgorithmVersion = shadowRiskAlgorithmVersion;
        this.servedRecommendationAlgorithmVersion = servedRecommendationAlgorithmVersion;
        this.shadowRecommendationAlgorithmVersion = shadowRecommendationAlgorithmVersion;
        this.randomSeed = randomSeed;
        this.selectionPropensity = selectionPropensity;
        this.configHash = configHash;
        this.featureSchemaVersion = featureSchemaVersion;
        this.allCandidatesJson = allCandidatesJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getRecommendationLogId() { return recommendationLogId; }
    public String getExperimentKey() { return experimentKey; }
    public String getVariant() { return variant; }
    public String getServedRiskAlgorithmVersion() { return servedRiskAlgorithmVersion; }
    public String getShadowRiskAlgorithmVersion() { return shadowRiskAlgorithmVersion; }
    public String getServedRecommendationAlgorithmVersion() {
        return servedRecommendationAlgorithmVersion;
    }
    public String getShadowRecommendationAlgorithmVersion() {
        return shadowRecommendationAlgorithmVersion;
    }
    public long getRandomSeed() { return randomSeed; }
    public Double getSelectionPropensity() { return selectionPropensity; }
    public String getConfigHash() { return configHash; }
    public int getFeatureSchemaVersion() { return featureSchemaVersion; }
    public String getAllCandidatesJson() { return allCandidatesJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
