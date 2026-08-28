package com.elma.gohan.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户长期口味画像仓库。 */
public interface UserTasteProfileRepository extends JpaRepository<UserTasteProfileEntity, UUID> {
}
