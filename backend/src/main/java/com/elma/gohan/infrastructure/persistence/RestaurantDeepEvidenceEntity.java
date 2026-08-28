package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 单家餐厅按来源保存的公开 Web Evidence 缓存。 */
@Entity
@Table(name = "restaurant_deep_evidence")
public class RestaurantDeepEvidenceEntity {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "source", nullable = false, length = 24)
    private String source;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "query_fingerprint", nullable = false, length = 64)
    private String queryFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceJson;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RestaurantDeepEvidenceEntity() { }

    public RestaurantDeepEvidenceEntity(UUID id, UUID restaurantId, String source,
                                        LocalDateTime now) {
        this.id = id;
        this.restaurantId = restaurantId;
        this.source = source;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void refresh(String status, String queryFingerprint, String evidenceJson,
                        LocalDateTime fetchedAt, LocalDateTime expiresAt,
                        LocalDateTime updatedAt) {
        this.status = status;
        this.queryFingerprint = queryFingerprint;
        this.evidenceJson = evidenceJson;
        this.fetchedAt = fetchedAt;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getQueryFingerprint() { return queryFingerprint; }
    public String getEvidenceJson() { return evidenceJson; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
