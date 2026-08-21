package com.elma.gohan.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFoodHistoryRepository extends JpaRepository<UserFoodHistoryEntity, UUID> {
    List<UserFoodHistoryEntity> findByAnonymousUserIdAndSelectedAtAfterOrderBySelectedAtDesc(
            UUID anonymousUserId, LocalDateTime after);
}
