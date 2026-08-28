package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 高德主实体与外部平台门店的匹配及 Evidence 缓存。 */
@Entity
@Table(name = "external_entity_mapping")
public class ExternalEntityMappingEntity {

    @Id
    private UUID id;
    @Column(name = "primary_source", length = 16, nullable = false)
    private String primarySource;
    @Column(name = "primary_poi_id", length = 64, nullable = false)
    private String primaryPoiId;
    @Column(name = "evidence_source", length = 16, nullable = false)
    private String evidenceSource;
    @Column(name = "evidence_poi_id", length = 128)
    private String evidencePoiId;
    @Column(name = "match_status", length = 16, nullable = false)
    private String matchStatus;
    @Column(name = "match_confidence")
    private Double matchConfidence;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_features_json", columnDefinition = "jsonb", nullable = false)
    private String matchFeaturesJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "v3_evidence_json", columnDefinition = "jsonb")
    private String v3EvidenceJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "v2_evidence_json", columnDefinition = "jsonb")
    private String v2EvidenceJson;
    @Column(name = "evidence_observed_at")
    private LocalDateTime evidenceObservedAt;
    @Column(name = "v2_observed_at")
    private LocalDateTime v2ObservedAt;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ExternalEntityMappingEntity() { }

    public ExternalEntityMappingEntity(UUID id, String primarySource, String primaryPoiId,
                                       String evidenceSource, LocalDateTime createdAt) {
        this.id = id;
        this.primarySource = primarySource;
        this.primaryPoiId = primaryPoiId;
        this.evidenceSource = evidenceSource;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void refresh(String evidencePoiId, String matchStatus, Double matchConfidence,
                        String matchFeaturesJson, String v3EvidenceJson, String v2EvidenceJson,
                        LocalDateTime evidenceObservedAt, LocalDateTime v2ObservedAt,
                        LocalDateTime expiresAt, LocalDateTime updatedAt) {
        this.evidencePoiId = evidencePoiId;
        this.matchStatus = matchStatus;
        this.matchConfidence = matchConfidence;
        this.matchFeaturesJson = matchFeaturesJson;
        this.v3EvidenceJson = v3EvidenceJson;
        this.v2EvidenceJson = v2EvidenceJson;
        this.evidenceObservedAt = evidenceObservedAt;
        this.v2ObservedAt = v2ObservedAt;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getPrimarySource() { return primarySource; }
    public String getPrimaryPoiId() { return primaryPoiId; }
    public String getEvidenceSource() { return evidenceSource; }
    public String getEvidencePoiId() { return evidencePoiId; }
    public String getMatchStatus() { return matchStatus; }
    public Double getMatchConfidence() { return matchConfidence; }
    public String getMatchFeaturesJson() { return matchFeaturesJson; }
    public String getV3EvidenceJson() { return v3EvidenceJson; }
    public String getV2EvidenceJson() { return v2EvidenceJson; }
    public LocalDateTime getEvidenceObservedAt() { return evidenceObservedAt; }
    public LocalDateTime getV2ObservedAt() { return v2ObservedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
