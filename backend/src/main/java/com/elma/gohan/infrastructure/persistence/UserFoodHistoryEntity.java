package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 用户已确认吃过的餐厅历史。 */
@Entity
@Table(name = "user_food_history")
public class UserFoodHistoryEntity {
    @Id private UUID id;
    @Column(name = "anonymous_user_id", nullable = false) private UUID anonymousUserId;
    @Column(name = "recommendation_log_id", nullable = false) private UUID recommendationLogId;
    @Column(name = "restaurant_id", nullable = false) private UUID restaurantId;
    @Column(name = "source", nullable = false) private String source;
    @Column(name = "source_poi_id", nullable = false) private String sourcePoiId;
    @Column(name = "category_code", nullable = false) private String categoryCode;
    @Column(name = "average_price") private Integer averagePrice;
    @Column(name = "feedback_result", nullable = false) private String feedbackResult;
    @Column(name = "selected_at", nullable = false) private LocalDateTime selectedAt;

    public UserFoodHistoryEntity() { }
    public UserFoodHistoryEntity(UUID id, UUID anonymousUserId, UUID recommendationLogId,
            UUID restaurantId, String source, String sourcePoiId, String categoryCode,
            Integer averagePrice, String feedbackResult, LocalDateTime selectedAt) {
        this.id = id; this.anonymousUserId = anonymousUserId;
        this.recommendationLogId = recommendationLogId; this.restaurantId = restaurantId;
        this.source = source; this.sourcePoiId = sourcePoiId; this.categoryCode = categoryCode;
        this.averagePrice = averagePrice; this.feedbackResult = feedbackResult;
        this.selectedAt = selectedAt;
    }
    public UUID getRestaurantId() { return restaurantId; }
    public String getSource() { return source; }
    public String getSourcePoiId() { return sourcePoiId; }
    public String getCategoryCode() { return categoryCode; }
    public String getFeedbackResult() { return feedbackResult; }
    public LocalDateTime getSelectedAt() { return selectedAt; }
}
