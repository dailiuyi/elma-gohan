package com.elma.gohan.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户近期饮食历史仓库。 */
public interface UserFoodHistoryRepository extends JpaRepository<UserFoodHistoryEntity, UUID> {
    List<UserFoodHistoryEntity> findByAnonymousUserIdAndSelectedAtAfterOrderBySelectedAtDesc(
            UUID anonymousUserId, LocalDateTime after);
}
