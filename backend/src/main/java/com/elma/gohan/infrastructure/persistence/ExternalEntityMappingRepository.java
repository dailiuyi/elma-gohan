package com.elma.gohan.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 外部门店映射与平台 Evidence 缓存仓库。 */
public interface ExternalEntityMappingRepository
        extends JpaRepository<ExternalEntityMappingEntity, UUID> {

    /** Atomic upsert: older/incomplete foreground work must not erase a fresh background match. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @org.springframework.data.jpa.repository.Query(value = """
        INSERT INTO external_entity_mapping(id,primary_source,primary_poi_id,evidence_source,evidence_poi_id,
          match_status,match_confidence,match_features_json,v3_evidence_json,v2_evidence_json,
          evidence_observed_at,v2_observed_at,expires_at,created_at,updated_at,match_algorithm_version)
        VALUES (:#{#value.id},:#{#value.primarySource},:#{#value.primaryPoiId},:#{#value.evidenceSource},
          :#{#value.evidencePoiId},:#{#value.matchStatus},:#{#value.matchConfidence},
          CAST(:#{#value.matchFeaturesJson} AS jsonb),CAST(:#{#value.v3EvidenceJson} AS jsonb),
          CAST(:#{#value.v2EvidenceJson} AS jsonb),:#{#value.evidenceObservedAt},:#{#value.v2ObservedAt},
          :#{#value.expiresAt},:#{#value.createdAt},:#{#value.updatedAt},:#{#value.matchAlgorithmVersion})
        ON CONFLICT(primary_source,primary_poi_id,evidence_source) DO UPDATE SET
          evidence_poi_id=excluded.evidence_poi_id,match_status=excluded.match_status,
          match_confidence=excluded.match_confidence,match_features_json=excluded.match_features_json,
          v3_evidence_json=excluded.v3_evidence_json,v2_evidence_json=excluded.v2_evidence_json,
          evidence_observed_at=excluded.evidence_observed_at,v2_observed_at=excluded.v2_observed_at,
          expires_at=excluded.expires_at,updated_at=excluded.updated_at,
          match_algorithm_version=excluded.match_algorithm_version
        WHERE external_entity_mapping.updated_at <= excluded.updated_at
          AND (external_entity_mapping.match_algorithm_version IS DISTINCT FROM excluded.match_algorithm_version
            OR external_entity_mapping.match_status <> 'MATCHED' OR excluded.match_status = 'MATCHED'
            OR external_entity_mapping.expires_at <= (now() AT TIME ZONE 'UTC')
            OR (excluded.match_status IN ('NO_MATCH','AMBIGUOUS') AND excluded.expires_at>excluded.updated_at))
        """, nativeQuery = true)
    void store(@org.springframework.data.repository.query.Param("value") ExternalEntityMappingEntity value);

    List<ExternalEntityMappingEntity>
    findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(
            String primarySource, Collection<String> primaryPoiIds, String evidenceSource);

    Optional<ExternalEntityMappingEntity>
    findByPrimarySourceAndPrimaryPoiIdAndEvidenceSource(
            String primarySource, String primaryPoiId, String evidenceSource);
}
