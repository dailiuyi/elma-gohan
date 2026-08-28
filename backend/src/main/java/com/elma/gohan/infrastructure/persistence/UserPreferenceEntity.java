package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 兼容旧版本的追加式用户偏好 JSON 快照。 */
@Entity
@Table(name = "user_preference")
public class UserPreferenceEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "anonymous_user_id", nullable = false)
    private UUID anonymousUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preference_json", columnDefinition = "jsonb", nullable = false)
    private String preferenceJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public UserPreferenceEntity() {
    }

    public UserPreferenceEntity(UUID id, UUID anonymousUserId, String preferenceJson,
                                LocalDateTime createdAt) {
        this.id = id;
        this.anonymousUserId = anonymousUserId;
        this.preferenceJson = preferenceJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getAnonymousUserId() { return anonymousUserId; }
    public String getPreferenceJson() { return preferenceJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
