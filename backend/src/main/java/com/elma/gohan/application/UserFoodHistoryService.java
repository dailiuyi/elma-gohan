package com.elma.gohan.application;

import com.elma.gohan.domain.recommendation.RecentFoodHistory;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.UserFoodHistoryEntity;
import com.elma.gohan.infrastructure.persistence.UserFoodHistoryRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 读取和记录用户近期确认吃过的餐厅。 */
@Service
public class UserFoodHistoryService {
    private final UserFoodHistoryRepository repository;
    public UserFoodHistoryService(UserFoodHistoryRepository repository) { this.repository = repository; }

    /** 加载最近 30 天饮食历史。 */
    public RecentFoodHistory load(UUID userId, LocalDateTime now) {
        var entries = repository.findByAnonymousUserIdAndSelectedAtAfterOrderBySelectedAtDesc(
                userId, now.minusDays(30)).stream()
                .map(e -> new RecentFoodHistory.Entry(e.getSource(), e.getSourcePoiId(),
                        e.getCategoryCode(), e.getFeedbackResult(), e.getSelectedAt()))
                .toList();
        return new RecentFoodHistory(entries, now);
    }

    /** 根据显式反馈记录一次已确认饮食。 */
    public void record(UUID userId, UUID recommendationId, RestaurantEntity restaurant,
            String result, LocalDateTime now) {
        repository.save(new UserFoodHistoryEntity(UUID.randomUUID(), userId, recommendationId,
                restaurant.getId(), restaurant.getSource(), restaurant.getSourcePoiId(),
                restaurant.getCategoryCode(), restaurant.getAveragePrice(), result, now));
    }
}
