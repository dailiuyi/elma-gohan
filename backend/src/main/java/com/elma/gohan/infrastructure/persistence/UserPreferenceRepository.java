package com.elma.gohan.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 旧版用户偏好快照仓库，仅用于迁移和兼容读取。 */
public interface UserPreferenceRepository extends JpaRepository<UserPreferenceEntity, UUID> {
    Optional<UserPreferenceEntity> findFirstByAnonymousUserIdOrderByCreatedAtDesc(UUID anonymousUserId);
}
