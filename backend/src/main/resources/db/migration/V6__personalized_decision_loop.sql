ALTER TABLE user_feedback
    ADD COLUMN flavor_tags_json JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE UNIQUE INDEX uq_user_feedback_session_restaurant
    ON user_feedback (recommendation_log_id, restaurant_id);

ALTER TABLE recommendation_log
    ADD COLUMN taste_algorithm_version VARCHAR(32) NOT NULL DEFAULT 'taste-v0.1',
    ADD COLUMN selection_mode VARCHAR(16) NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE recommendation_log
    ALTER COLUMN taste_algorithm_version DROP DEFAULT,
    ALTER COLUMN selection_mode DROP DEFAULT;

ALTER TABLE recommendation_candidate
    ADD COLUMN taste_match_score DOUBLE PRECISION NOT NULL DEFAULT 50.0,
    ADD COLUMN taste_confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN score_breakdown_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN personalization_reasons_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN selection_mode VARCHAR(16) NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE recommendation_candidate
    ALTER COLUMN taste_match_score DROP DEFAULT,
    ALTER COLUMN taste_confidence DROP DEFAULT,
    ALTER COLUMN score_breakdown_json DROP DEFAULT,
    ALTER COLUMN personalization_reasons_json DROP DEFAULT,
    ALTER COLUMN selection_mode DROP DEFAULT;

CREATE TABLE user_taste_profile (
    anonymous_user_id          UUID PRIMARY KEY,
    schema_version             INTEGER NOT NULL,
    taste_algorithm_version    VARCHAR(32) NOT NULL,
    category_weights_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    flavor_weights_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    price_weights_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    distance_weights_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    implicit_accumulators_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    explicit_feedback_count    INTEGER NOT NULL DEFAULT 0,
    implicit_behavior_count    INTEGER NOT NULL DEFAULT 0,
    last_decayed_at            TIMESTAMP NOT NULL,
    last_feedback_at           TIMESTAMP,
    updated_at                 TIMESTAMP NOT NULL,
    version                    BIGINT NOT NULL DEFAULT 0
);

INSERT INTO user_taste_profile (
    anonymous_user_id, schema_version, taste_algorithm_version,
    category_weights_json, flavor_weights_json, price_weights_json,
    distance_weights_json, implicit_accumulators_json,
    explicit_feedback_count, implicit_behavior_count,
    last_decayed_at, last_feedback_at, updated_at, version
)
SELECT DISTINCT ON (anonymous_user_id)
    anonymous_user_id,
    3,
    'taste-v0.1',
    COALESCE(preference_json -> 'categoryWeights', '{}'::jsonb),
    '{}'::jsonb,
    COALESCE(preference_json -> 'priceBandWeights', '{}'::jsonb),
    COALESCE(preference_json -> 'distanceBandWeights', '{}'::jsonb),
    '{}'::jsonb,
    COALESCE((preference_json ->> 'feedbackCount')::integer, 0),
    0,
    created_at,
    created_at,
    created_at,
    0
FROM user_preference
WHERE preference_json ->> 'schemaVersion' = '2'
ORDER BY anonymous_user_id, created_at DESC;

CREATE TABLE user_behavior (
    id                               UUID PRIMARY KEY,
    anonymous_user_id                UUID NOT NULL,
    recommendation_log_id            UUID NOT NULL REFERENCES recommendation_log (id) ON DELETE CASCADE,
    restaurant_id                    UUID NOT NULL REFERENCES restaurant (id),
    behavior_type                    VARCHAR(16) NOT NULL,
    source                           VARCHAR(16) NOT NULL,
    feature_snapshot_json            JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_algorithm_version           VARCHAR(32) NOT NULL,
    recommendation_algorithm_version VARCHAR(32) NOT NULL,
    taste_algorithm_version          VARCHAR(32) NOT NULL,
    occurred_at                      TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_behavior_session_restaurant_type
        UNIQUE (recommendation_log_id, restaurant_id, behavior_type)
);

CREATE INDEX idx_user_behavior_user_time
    ON user_behavior (anonymous_user_id, occurred_at DESC);

CREATE TABLE user_food_history (
    id                    UUID PRIMARY KEY,
    anonymous_user_id     UUID NOT NULL,
    recommendation_log_id UUID NOT NULL REFERENCES recommendation_log (id) ON DELETE CASCADE,
    restaurant_id         UUID NOT NULL REFERENCES restaurant (id),
    source                VARCHAR(16) NOT NULL,
    source_poi_id         VARCHAR(64) NOT NULL,
    category_code         VARCHAR(32) NOT NULL,
    average_price         INTEGER,
    feedback_result       VARCHAR(16) NOT NULL,
    selected_at           TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_food_history_session_restaurant
        UNIQUE (recommendation_log_id, restaurant_id)
);

CREATE INDEX idx_user_food_history_user_time
    ON user_food_history (anonymous_user_id, selected_at DESC);

CREATE TABLE restaurant_flavor_observation (
    restaurant_id      UUID NOT NULL REFERENCES restaurant (id) ON DELETE CASCADE,
    anonymous_user_id  UUID NOT NULL,
    flavor_tag         VARCHAR(16) NOT NULL,
    feedback_result    VARCHAR(16) NOT NULL,
    observed_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (restaurant_id, anonymous_user_id, flavor_tag)
);

CREATE INDEX idx_restaurant_flavor_public
    ON restaurant_flavor_observation (restaurant_id, flavor_tag);

CREATE VIEW v_risk_calibration AS
SELECT
    rc.risk_algorithm_version,
    CASE
        WHEN rc.risk_score <= 20 THEN '[0,20]'
        WHEN rc.risk_score <= 40 THEN '(20,40]'
        WHEN rc.risk_score <= 60 THEN '(40,60]'
        ELSE '>60'
    END AS risk_bucket,
    COUNT(*) AS feedback_count,
    COUNT(*) FILTER (WHERE uf.result = 'DISLIKE') AS dislike_count,
    ROUND(COUNT(*) FILTER (WHERE uf.result = 'DISLIKE')::numeric / NULLIF(COUNT(*), 0), 4)
        AS dislike_rate
FROM user_feedback uf
JOIN recommendation_candidate rc
  ON rc.recommendation_log_id = uf.recommendation_log_id
 AND rc.restaurant_id = uf.restaurant_id
GROUP BY rc.risk_algorithm_version, risk_bucket;

CREATE VIEW v_recommendation_metrics AS
WITH behavior AS (
    SELECT recommendation_log_id,
           BOOL_OR(behavior_type = 'ACCEPT') AS accepted,
           BOOL_OR(behavior_type = 'NAVIGATE') AS navigated,
           COUNT(*) FILTER (WHERE behavior_type = 'REROLL') AS reroll_count
    FROM user_behavior
    GROUP BY recommendation_log_id
), feedback AS (
    SELECT recommendation_log_id,
           BOOL_OR(result = 'DISLIKE') AS disliked
    FROM user_feedback
    GROUP BY recommendation_log_id
)
SELECT
    rl.created_at::date AS metric_date,
    rl.recommendation_algorithm_version,
    rl.taste_algorithm_version,
    rl.selection_mode,
    COUNT(*) AS recommendation_count,
    ROUND(COUNT(*) FILTER (WHERE COALESCE(b.accepted, FALSE))::numeric / COUNT(*), 4)
        AS acceptance_rate,
    ROUND(COUNT(*) FILTER (WHERE COALESCE(b.navigated, FALSE))::numeric / COUNT(*), 4)
        AS navigation_rate,
    ROUND(AVG(COALESCE(b.reroll_count, 0))::numeric, 4) AS average_reroll_count,
    ROUND(COUNT(*) FILTER (WHERE COALESCE(f.disliked, FALSE))::numeric / COUNT(*), 4)
        AS dislike_rate
FROM recommendation_log rl
LEFT JOIN behavior b ON b.recommendation_log_id = rl.id
LEFT JOIN feedback f ON f.recommendation_log_id = rl.id
GROUP BY rl.created_at::date, rl.recommendation_algorithm_version,
         rl.taste_algorithm_version, rl.selection_mode;
