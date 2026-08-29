CREATE TABLE recommendation_decision_snapshot (
    id                                      UUID PRIMARY KEY,
    recommendation_log_id                   UUID NOT NULL
        REFERENCES recommendation_log (id) ON DELETE CASCADE,
    experiment_key                          VARCHAR(64) NOT NULL,
    variant                                 VARCHAR(32) NOT NULL,
    served_risk_algorithm_version           VARCHAR(32) NOT NULL,
    shadow_risk_algorithm_version           VARCHAR(32) NOT NULL,
    served_recommendation_algorithm_version VARCHAR(32) NOT NULL,
    shadow_recommendation_algorithm_version VARCHAR(32) NOT NULL,
    random_seed                             BIGINT NOT NULL,
    selection_propensity                    DOUBLE PRECISION,
    config_hash                             VARCHAR(128) NOT NULL,
    feature_schema_version                  INTEGER NOT NULL,
    all_candidates_json                     JSONB NOT NULL,
    created_at                              TIMESTAMP NOT NULL,
    CONSTRAINT uq_decision_snapshot_log_experiment
        UNIQUE (recommendation_log_id, experiment_key),
    CONSTRAINT ck_decision_snapshot_variant
        CHECK (variant IN ('SHADOW', 'CONTROL', 'CANDIDATE')),
    CONSTRAINT ck_decision_snapshot_propensity
        CHECK (selection_propensity IS NULL
            OR (selection_propensity >= 0.0 AND selection_propensity <= 1.0)),
    CONSTRAINT ck_decision_snapshot_feature_schema
        CHECK (feature_schema_version > 0),
    CONSTRAINT ck_decision_snapshot_payload_object
        CHECK (jsonb_typeof(all_candidates_json) = 'object'),
    CONSTRAINT ck_decision_snapshot_candidates_array
        CHECK (all_candidates_json ? 'candidates'
            AND jsonb_typeof(all_candidates_json -> 'candidates') = 'array')
);

CREATE INDEX idx_decision_snapshot_experiment_time
    ON recommendation_decision_snapshot (experiment_key, variant, created_at DESC);
