package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recommendation_log")
public class RecommendationLogEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "anonymous_user_id", nullable = false)
    private UUID anonymousUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_condition_json", columnDefinition = "jsonb", nullable = false)
    private String requestConditionJson;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "current_restaurant_id", nullable = false)
    private UUID currentRestaurantId;

    @Column(name = "recommended_restaurant_id", nullable = false)
    private UUID recommendedRestaurantId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "low_regret_score", nullable = false)
    private double lowRegretScore;

    @Column(name = "risk_algorithm_version", length = 32, nullable = false)
    private String riskAlgorithmVersion;

    @Column(name = "recommendation_algorithm_version", length = 32, nullable = false)
    private String recommendationAlgorithmVersion;

    @Column(name = "taste_algorithm_version", length = 32, nullable = false)
    private String tasteAlgorithmVersion;

    @Column(name = "selection_mode", length = 16, nullable = false)
    private String selectionMode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RecommendationLogEntity() {
    }

    public RecommendationLogEntity(UUID id, UUID anonymousUserId, String requestConditionJson,
                                   int candidateCount, UUID currentRestaurantId,
                                   UUID recommendedRestaurantId, int riskScore,
                                   double lowRegretScore, String riskAlgorithmVersion,
                                   String recommendationAlgorithmVersion, LocalDateTime createdAt) {
        this(id, anonymousUserId, requestConditionJson, candidateCount, currentRestaurantId,
                recommendedRestaurantId, riskScore, lowRegretScore, riskAlgorithmVersion,
                recommendationAlgorithmVersion, "taste-v0.1", "DEFAULT", createdAt);
    }

    public RecommendationLogEntity(UUID id, UUID anonymousUserId, String requestConditionJson,
                                   int candidateCount, UUID currentRestaurantId,
                                   UUID recommendedRestaurantId, int riskScore,
                                   double lowRegretScore, String riskAlgorithmVersion,
                                   String recommendationAlgorithmVersion,
                                   String tasteAlgorithmVersion, String selectionMode,
                                   LocalDateTime createdAt) {
        this.id = id;
        this.anonymousUserId = anonymousUserId;
        this.requestConditionJson = requestConditionJson;
        this.candidateCount = candidateCount;
        this.currentRestaurantId = currentRestaurantId;
        this.recommendedRestaurantId = recommendedRestaurantId;
        this.riskScore = riskScore;
        this.lowRegretScore = lowRegretScore;
        this.riskAlgorithmVersion = riskAlgorithmVersion;
        this.recommendationAlgorithmVersion = recommendationAlgorithmVersion;
        this.tasteAlgorithmVersion = tasteAlgorithmVersion;
        this.selectionMode = selectionMode;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getAnonymousUserId() { return anonymousUserId; }
    public String getRequestConditionJson() { return requestConditionJson; }
    public int getCandidateCount() { return candidateCount; }
    public UUID getCurrentRestaurantId() { return currentRestaurantId; }
    public UUID getRecommendedRestaurantId() { return recommendedRestaurantId; }
    public int getRiskScore() { return riskScore; }
    public double getLowRegretScore() { return lowRegretScore; }
    public String getRiskAlgorithmVersion() { return riskAlgorithmVersion; }
    public String getRecommendationAlgorithmVersion() { return recommendationAlgorithmVersion; }
    public String getTasteAlgorithmVersion() { return tasteAlgorithmVersion; }
    public String getSelectionMode() { return selectionMode; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void updateCurrent(UUID currentRestaurantId) {
        this.currentRestaurantId = currentRestaurantId;
    }
}
