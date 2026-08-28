package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 用户长期口味画像及乐观锁版本。 */
@Entity
@Table(name = "user_taste_profile")
public class UserTasteProfileEntity {
    @Id
    @Column(name = "anonymous_user_id")
    private UUID anonymousUserId;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(name = "taste_algorithm_version", nullable = false)
    private String tasteAlgorithmVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_weights_json", columnDefinition = "jsonb", nullable = false)
    private String categoryWeightsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flavor_weights_json", columnDefinition = "jsonb", nullable = false)
    private String flavorWeightsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "price_weights_json", columnDefinition = "jsonb", nullable = false)
    private String priceWeightsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "distance_weights_json", columnDefinition = "jsonb", nullable = false)
    private String distanceWeightsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "implicit_accumulators_json", columnDefinition = "jsonb", nullable = false)
    private String implicitAccumulatorsJson;
    @Column(name = "explicit_feedback_count", nullable = false)
    private int explicitFeedbackCount;
    @Column(name = "implicit_behavior_count", nullable = false)
    private int implicitBehaviorCount;
    @Column(name = "last_decayed_at", nullable = false)
    private LocalDateTime lastDecayedAt;
    @Column(name = "last_feedback_at")
    private LocalDateTime lastFeedbackAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UserTasteProfileEntity() { }

    public UserTasteProfileEntity(UUID anonymousUserId, int schemaVersion,
            String tasteAlgorithmVersion, String categoryWeightsJson,
            String flavorWeightsJson, String priceWeightsJson, String distanceWeightsJson,
            String implicitAccumulatorsJson, int explicitFeedbackCount,
            int implicitBehaviorCount, LocalDateTime lastDecayedAt,
            LocalDateTime lastFeedbackAt, LocalDateTime updatedAt) {
        this.anonymousUserId = anonymousUserId;
        this.schemaVersion = schemaVersion;
        this.tasteAlgorithmVersion = tasteAlgorithmVersion;
        this.categoryWeightsJson = categoryWeightsJson;
        this.flavorWeightsJson = flavorWeightsJson;
        this.priceWeightsJson = priceWeightsJson;
        this.distanceWeightsJson = distanceWeightsJson;
        this.implicitAccumulatorsJson = implicitAccumulatorsJson;
        this.explicitFeedbackCount = explicitFeedbackCount;
        this.implicitBehaviorCount = implicitBehaviorCount;
        this.lastDecayedAt = lastDecayedAt;
        this.lastFeedbackAt = lastFeedbackAt;
        this.updatedAt = updatedAt;
    }

    public UUID getAnonymousUserId() { return anonymousUserId; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getTasteAlgorithmVersion() { return tasteAlgorithmVersion; }
    public String getCategoryWeightsJson() { return categoryWeightsJson; }
    public String getFlavorWeightsJson() { return flavorWeightsJson; }
    public String getPriceWeightsJson() { return priceWeightsJson; }
    public String getDistanceWeightsJson() { return distanceWeightsJson; }
    public String getImplicitAccumulatorsJson() { return implicitAccumulatorsJson; }
    public int getExplicitFeedbackCount() { return explicitFeedbackCount; }
    public int getImplicitBehaviorCount() { return implicitBehaviorCount; }
    public LocalDateTime getLastDecayedAt() { return lastDecayedAt; }
    public LocalDateTime getLastFeedbackAt() { return lastFeedbackAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void replace(int schemaVersion, String tasteAlgorithmVersion,
            String categoryWeightsJson, String flavorWeightsJson, String priceWeightsJson,
            String distanceWeightsJson, String implicitAccumulatorsJson,
            int explicitFeedbackCount, int implicitBehaviorCount,
            LocalDateTime lastDecayedAt, LocalDateTime lastFeedbackAt,
            LocalDateTime updatedAt) {
        this.schemaVersion = schemaVersion;
        this.tasteAlgorithmVersion = tasteAlgorithmVersion;
        this.categoryWeightsJson = categoryWeightsJson;
        this.flavorWeightsJson = flavorWeightsJson;
        this.priceWeightsJson = priceWeightsJson;
        this.distanceWeightsJson = distanceWeightsJson;
        this.implicitAccumulatorsJson = implicitAccumulatorsJson;
        this.explicitFeedbackCount = explicitFeedbackCount;
        this.implicitBehaviorCount = implicitBehaviorCount;
        this.lastDecayedAt = lastDecayedAt;
        this.lastFeedbackAt = lastFeedbackAt;
        this.updatedAt = updatedAt;
    }
}
