package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 餐厅公开线索的派生分析缓存。 */
@Entity
@Table(name = "restaurant_deep_analysis")
public class RestaurantDeepAnalysisEntity {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "evidence_fingerprint", nullable = false, length = 64)
    private String evidenceFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_json", nullable = false, columnDefinition = "jsonb")
    private String analysisJson;

    @Column(name = "algorithm_version", nullable = false, length = 32)
    private String algorithmVersion;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RestaurantDeepAnalysisEntity() { }

    public RestaurantDeepAnalysisEntity(UUID id, UUID restaurantId, LocalDateTime now) {
        this.id = id;
        this.restaurantId = restaurantId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void refresh(String evidenceFingerprint, String analysisJson,
                        String algorithmVersion, LocalDateTime generatedAt,
                        LocalDateTime expiresAt, LocalDateTime updatedAt) {
        this.evidenceFingerprint = evidenceFingerprint;
        this.analysisJson = analysisJson;
        this.algorithmVersion = algorithmVersion;
        this.generatedAt = generatedAt;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public String getEvidenceFingerprint() { return evidenceFingerprint; }
    public String getAnalysisJson() { return analysisJson; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
