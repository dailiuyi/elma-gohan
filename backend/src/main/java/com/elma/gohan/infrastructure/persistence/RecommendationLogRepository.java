package com.elma.gohan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 推荐会话日志仓库。 */
public interface RecommendationLogRepository extends JpaRepository<RecommendationLogEntity, UUID> {

    Optional<RecommendationLogEntity> findByIdAndAnonymousUserId(UUID id, UUID anonymousUserId);
}
