package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 推荐会话日志仓库。 */
public interface RecommendationLogRepository extends JpaRepository<RecommendationLogEntity, UUID> {

    Optional<RecommendationLogEntity> findByIdAndAnonymousUserId(UUID id, UUID anonymousUserId);

    /** 串行化同一推荐会话的 reroll 游标推进。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecommendationLogEntity r "
            + "where r.id = :id and r.anonymousUserId = :anonymousUserId")
    Optional<RecommendationLogEntity> findByIdAndAnonymousUserIdForUpdate(
            @Param("id") UUID id,
            @Param("anonymousUserId") UUID anonymousUserId);
}
