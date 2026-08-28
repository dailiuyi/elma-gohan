package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 匿名用户对餐厅口味标签的观察记录。 */
@Entity
@Table(name = "restaurant_flavor_observation")
@IdClass(RestaurantFlavorObservationEntity.Key.class)
public class RestaurantFlavorObservationEntity {
    @Id @Column(name = "restaurant_id") private UUID restaurantId;
    @Id @Column(name = "anonymous_user_id") private UUID anonymousUserId;
    @Id @Column(name = "flavor_tag") private String flavorTag;
    @Column(name = "feedback_result", nullable = false) private String feedbackResult;
    @Column(name = "observed_at", nullable = false) private LocalDateTime observedAt;

    public RestaurantFlavorObservationEntity() { }
    public RestaurantFlavorObservationEntity(UUID restaurantId, UUID anonymousUserId,
            String flavorTag, String feedbackResult, LocalDateTime observedAt) {
        this.restaurantId = restaurantId; this.anonymousUserId = anonymousUserId;
        this.flavorTag = flavorTag; this.feedbackResult = feedbackResult;
        this.observedAt = observedAt;
    }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getAnonymousUserId() { return anonymousUserId; }
    public String getFlavorTag() { return flavorTag; }

    public static class Key implements Serializable {
        private UUID restaurantId; private UUID anonymousUserId; private String flavorTag;
        public Key() { }
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return Objects.equals(restaurantId, k.restaurantId)
                    && Objects.equals(anonymousUserId, k.anonymousUserId)
                    && Objects.equals(flavorTag, k.flavorTag);
        }
        public int hashCode() { return Objects.hash(restaurantId, anonymousUserId, flavorTag); }
    }
}
