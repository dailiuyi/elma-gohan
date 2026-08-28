package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 用户显式与隐式行为事件及候选特征快照。 */
@Entity
@Table(name = "user_behavior")
public class UserBehaviorEntity {
    @Id private UUID id;
    @Column(name = "anonymous_user_id", nullable = false) private UUID anonymousUserId;
    @Column(name = "recommendation_log_id", nullable = false) private UUID recommendationLogId;
    @Column(name = "restaurant_id", nullable = false) private UUID restaurantId;
    @Column(name = "behavior_type", nullable = false) private String behaviorType;
    @Column(name = "source", nullable = false) private String source;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_snapshot_json", columnDefinition = "jsonb", nullable = false)
    private String featureSnapshotJson;
    @Column(name = "risk_algorithm_version", nullable = false) private String riskAlgorithmVersion;
    @Column(name = "recommendation_algorithm_version", nullable = false)
    private String recommendationAlgorithmVersion;
    @Column(name = "taste_algorithm_version", nullable = false) private String tasteAlgorithmVersion;
    @Column(name = "occurred_at", nullable = false) private LocalDateTime occurredAt;

    public UserBehaviorEntity() { }
    public UserBehaviorEntity(UUID id, UUID anonymousUserId, UUID recommendationLogId,
            UUID restaurantId, String behaviorType, String source, String featureSnapshotJson,
            String riskAlgorithmVersion, String recommendationAlgorithmVersion,
            String tasteAlgorithmVersion, LocalDateTime occurredAt) {
        this.id = id; this.anonymousUserId = anonymousUserId;
        this.recommendationLogId = recommendationLogId; this.restaurantId = restaurantId;
        this.behaviorType = behaviorType; this.source = source;
        this.featureSnapshotJson = featureSnapshotJson;
        this.riskAlgorithmVersion = riskAlgorithmVersion;
        this.recommendationAlgorithmVersion = recommendationAlgorithmVersion;
        this.tasteAlgorithmVersion = tasteAlgorithmVersion; this.occurredAt = occurredAt;
    }
    public UUID getId() { return id; }
    public UUID getAnonymousUserId() { return anonymousUserId; }
    public UUID getRecommendationLogId() { return recommendationLogId; }
    public UUID getRestaurantId() { return restaurantId; }
    public String getBehaviorType() { return behaviorType; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
