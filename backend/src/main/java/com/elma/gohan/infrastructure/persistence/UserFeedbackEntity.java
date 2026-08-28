package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import com.elma.gohan.domain.recommendation.FlavorTag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 用户对推荐餐厅的显式反馈。 */
@Entity
@Table(name = "user_feedback")
public class UserFeedbackEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "recommendation_log_id", nullable = false)
    private UUID recommendationLogId;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "anonymous_user_id", nullable = false)
    private UUID anonymousUserId;

    @Column(name = "result", length = 16, nullable = false)
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flavor_tags_json", columnDefinition = "jsonb", nullable = false)
    private String flavorTagsJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public UserFeedbackEntity() {
    }

    public UserFeedbackEntity(UUID id, UUID recommendationLogId, UUID restaurantId,
                              UUID anonymousUserId, String result, LocalDateTime createdAt) {
        this(id, recommendationLogId, restaurantId, anonymousUserId, result, "[]", createdAt);
    }

    public UserFeedbackEntity(UUID id, UUID recommendationLogId, UUID restaurantId,
                              UUID anonymousUserId, String result, String flavorTagsJson,
                              LocalDateTime createdAt) {
        this.id = id;
        this.recommendationLogId = recommendationLogId;
        this.restaurantId = restaurantId;
        this.anonymousUserId = anonymousUserId;
        this.result = result;
        this.flavorTagsJson = flavorTagsJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getRecommendationLogId() { return recommendationLogId; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getAnonymousUserId() { return anonymousUserId; }
    public String getResult() { return result; }
    public String getFlavorTagsJson() { return flavorTagsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public List<FlavorTag> flavorTags(ObjectMapper objectMapper) {
        if (flavorTagsJson == null || flavorTagsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(flavorTagsJson, new TypeReference<List<FlavorTag>>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
