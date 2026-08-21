package com.elma.gohan.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTasteProfileRepository extends JpaRepository<UserTasteProfileEntity, UUID> {
}
