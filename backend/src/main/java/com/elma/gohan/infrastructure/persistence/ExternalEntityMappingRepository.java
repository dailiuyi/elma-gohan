package com.elma.gohan.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 外部门店映射与平台 Evidence 缓存仓库。 */
public interface ExternalEntityMappingRepository
        extends JpaRepository<ExternalEntityMappingEntity, UUID> {

    List<ExternalEntityMappingEntity>
    findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(
            String primarySource, Collection<String> primaryPoiIds, String evidenceSource);

    Optional<ExternalEntityMappingEntity>
    findByPrimarySourceAndPrimaryPoiIdAndEvidenceSource(
            String primarySource, String primaryPoiId, String evidenceSource);
}
