package com.elma.gohan.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 客观风险结果仓库。 */
public interface RiskResultRepository extends JpaRepository<RiskResultEntity, UUID> {
}
