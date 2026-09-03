"""Read-only PostgreSQL query registry for the offline operations dashboard."""

from __future__ import annotations

from dataclasses import dataclass
import re


@dataclass(frozen=True)
class QuerySpec:
    name: str
    sql: str
    max_rows: int


CAPABILITIES = QuerySpec(
    "capabilities",
    """
    SELECT
      to_regclass('public.recommendation_log') IS NOT NULL AS has_recommendation_log,
      to_regclass('public.restaurant') IS NOT NULL AS has_restaurant,
      to_regclass('public.user_feedback') IS NOT NULL AS has_user_feedback,
      to_regclass('public.user_behavior') IS NOT NULL AS has_user_behavior,
      to_regclass('public.user_taste_profile') IS NOT NULL AS has_user_taste_profile,
      to_regclass('public.user_food_history') IS NOT NULL AS has_user_food_history,
      to_regclass('public.restaurant_flavor_observation') IS NOT NULL AS has_flavor_observation,
      to_regclass('public.external_entity_mapping') IS NOT NULL AS has_external_mapping,
      to_regclass('public.restaurant_deep_evidence') IS NOT NULL AS has_deep_evidence,
      to_regclass('public.restaurant_deep_analysis') IS NOT NULL AS has_deep_analysis,
      to_regclass('public.recommendation_decision_snapshot') IS NOT NULL AS has_shadow_snapshot,
      to_regclass('public.flyway_schema_history') IS NOT NULL AS has_flyway_history,
      to_regclass('public.v_recommendation_metrics') IS NOT NULL AS has_recommendation_metrics_view,
      to_regclass('public.v_risk_calibration') IS NOT NULL AS has_risk_calibration_view,
      EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'recommendation_log'
          AND column_name = 'selection_mode'
      ) AS has_selection_mode
    """,
    1,
)

CONNECTION_META = QuerySpec(
    "connection_meta",
    """
    SELECT
      current_database() AS database_name,
      current_setting('server_version') AS server_version,
      current_setting('TimeZone') AS timezone,
      current_setting('transaction_read_only') AS transaction_read_only,
      CURRENT_TIMESTAMP AS snapshot_at
    """,
    1,
)

FLYWAY_VERSION = QuerySpec(
    "flyway_version",
    """
    SELECT
      COALESCE(max(version::integer), 0) AS flyway_version,
      count(*) AS migration_count
    FROM flyway_schema_history
    WHERE success
      AND version ~ '^[0-9]+$'
    """,
    1,
)

OVERVIEW = QuerySpec(
    "overview",
    """
    WITH bounds AS (
      SELECT CURRENT_DATE - (%s::integer - 1) AS start_date
    ), per_user AS (
      SELECT
        anonymous_user_id,
        count(*) AS request_count,
        min(created_at) AS first_seen,
        max(created_at) AS last_seen
      FROM recommendation_log
      GROUP BY anonymous_user_id
    ), totals AS (
      SELECT
        count(*) AS total_recommendations,
        count(DISTINCT anonymous_user_id) AS total_anonymous_ids,
        round(avg(candidate_count)::numeric, 2) AS average_candidate_count,
        min(created_at) AS first_request_at,
        max(created_at) AS last_request_at
      FROM recommendation_log
    ), period AS (
      SELECT
        count(*) AS period_recommendations,
        count(DISTINCT anonymous_user_id) AS period_active_ids
      FROM recommendation_log, bounds
      WHERE created_at >= bounds.start_date
    )
    SELECT
      t.total_recommendations,
      t.total_anonymous_ids,
      (SELECT count(*) FROM restaurant) AS total_restaurants,
      (SELECT count(*) FROM user_feedback) AS total_feedbacks,
      count(pu.anonymous_user_id) FILTER (WHERE pu.request_count >= 2) AS total_returning_ids,
      round(
        count(pu.anonymous_user_id) FILTER (WHERE pu.request_count >= 2)::numeric
        / nullif(t.total_anonymous_ids, 0), 4
      ) AS total_returning_rate,
      round(avg(pu.request_count)::numeric, 2) AS average_requests_per_id,
      t.average_candidate_count,
      t.first_request_at,
      t.last_request_at,
      p.period_recommendations,
      p.period_active_ids,
      count(pu.anonymous_user_id) FILTER (
        WHERE pu.first_seen >= b.start_date
      ) AS period_new_ids,
      count(pu.anonymous_user_id) FILTER (
        WHERE pu.last_seen >= b.start_date AND pu.first_seen < b.start_date
      ) AS period_returning_ids
    FROM totals t
    CROSS JOIN period p
    CROSS JOIN bounds b
    LEFT JOIN per_user pu ON true
    GROUP BY
      t.total_recommendations, t.total_anonymous_ids,
      t.average_candidate_count, t.first_request_at, t.last_request_at,
      p.period_recommendations, p.period_active_ids, b.start_date
    """,
    1,
)

FUNNEL_V1 = QuerySpec(
    "funnel_v1",
    """
    WITH bounds AS (
      SELECT CURRENT_DATE - (%s::integer - 1) AS start_date
    ), period_sessions AS (
      SELECT id
      FROM recommendation_log, bounds
      WHERE created_at >= bounds.start_date
    ), feedback AS (
      SELECT
        uf.recommendation_log_id,
        count(*) AS feedback_count,
        bool_or(uf.result = 'DISLIKE') AS disliked
      FROM user_feedback uf
      JOIN period_sessions ps ON ps.id = uf.recommendation_log_id
      GROUP BY uf.recommendation_log_id
    )
    SELECT
      count(ps.id) AS recommendation_sessions,
      count(ps.id) FILTER (WHERE f.recommendation_log_id IS NOT NULL) AS feedback_sessions,
      coalesce(sum(f.feedback_count), 0) AS feedback_count,
      count(ps.id) FILTER (WHERE coalesce(f.disliked, false)) AS disliked_sessions,
      round(
        count(ps.id) FILTER (WHERE f.recommendation_log_id IS NOT NULL)::numeric
        / nullif(count(ps.id), 0), 4
      ) AS feedback_rate
    FROM period_sessions ps
    LEFT JOIN feedback f ON f.recommendation_log_id = ps.id
    """,
    1,
)

FUNNEL_V6 = QuerySpec(
    "funnel_v6",
    """
    WITH bounds AS (
      SELECT CURRENT_DATE - (%s::integer - 1) AS start_date
    ), period_sessions AS (
      SELECT id
      FROM recommendation_log, bounds
      WHERE created_at >= bounds.start_date
    ), behavior AS (
      SELECT
        ub.recommendation_log_id,
        bool_or(ub.behavior_type = 'ACCEPT') AS accepted,
        bool_or(ub.behavior_type = 'NAVIGATE') AS navigated,
        count(*) FILTER (WHERE ub.behavior_type = 'REROLL') AS reroll_count
      FROM user_behavior ub
      JOIN period_sessions ps ON ps.id = ub.recommendation_log_id
      GROUP BY ub.recommendation_log_id
    ), feedback AS (
      SELECT
        uf.recommendation_log_id,
        count(*) AS feedback_count,
        bool_or(uf.result = 'DISLIKE') AS disliked
      FROM user_feedback uf
      JOIN period_sessions ps ON ps.id = uf.recommendation_log_id
      GROUP BY uf.recommendation_log_id
    )
    SELECT
      count(ps.id) AS recommendation_sessions,
      count(ps.id) FILTER (WHERE coalesce(b.accepted, false)) AS accepted_sessions,
      count(ps.id) FILTER (WHERE coalesce(b.navigated, false)) AS navigated_sessions,
      count(ps.id) FILTER (WHERE coalesce(b.reroll_count, 0) > 0) AS rerolled_sessions,
      count(ps.id) FILTER (WHERE f.recommendation_log_id IS NOT NULL) AS feedback_sessions,
      coalesce(sum(f.feedback_count), 0) AS feedback_count,
      count(ps.id) FILTER (WHERE coalesce(f.disliked, false)) AS disliked_sessions,
      coalesce(sum(b.reroll_count), 0) AS reroll_count,
      round(avg(coalesce(b.reroll_count, 0))::numeric, 2) AS average_rerolls,
      round(
        count(ps.id) FILTER (WHERE coalesce(b.accepted, false))::numeric
        / nullif(count(ps.id), 0), 4
      ) AS acceptance_rate,
      round(
        count(ps.id) FILTER (WHERE coalesce(b.navigated, false))::numeric
        / nullif(count(ps.id), 0), 4
      ) AS navigation_rate,
      round(
        count(ps.id) FILTER (WHERE f.recommendation_log_id IS NOT NULL)::numeric
        / nullif(count(ps.id), 0), 4
      ) AS feedback_rate
    FROM period_sessions ps
    LEFT JOIN behavior b ON b.recommendation_log_id = ps.id
    LEFT JOIN feedback f ON f.recommendation_log_id = ps.id
    """,
    1,
)

DAILY_V1 = QuerySpec(
    "daily_v1",
    """
    WITH bounds AS (
      SELECT CURRENT_DATE - (%s::integer - 1) AS start_date
    ), days AS (
      SELECT generate_series(
        (SELECT start_date FROM bounds), CURRENT_DATE, interval '1 day'
      )::date AS metric_date
    ), first_seen AS (
      SELECT anonymous_user_id, min(created_at)::date AS first_date
      FROM recommendation_log
      GROUP BY anonymous_user_id
    ), recommendation_daily AS (
      SELECT
        created_at::date AS metric_date,
        count(*) AS recommendations,
        count(DISTINCT anonymous_user_id) AS active_ids
      FROM recommendation_log, bounds
      WHERE created_at >= bounds.start_date
      GROUP BY created_at::date
    ), new_id_daily AS (
      SELECT first_date AS metric_date, count(*) AS new_ids
      FROM first_seen, bounds
      WHERE first_date >= bounds.start_date
      GROUP BY first_date
    ), feedback_daily AS (
      SELECT
        created_at::date AS metric_date,
        count(*) AS feedbacks,
        count(*) FILTER (WHERE result = 'DISLIKE') AS dislikes
      FROM user_feedback, bounds
      WHERE created_at >= bounds.start_date
      GROUP BY created_at::date
    )
    SELECT
      d.metric_date,
      coalesce(r.recommendations, 0) AS recommendations,
      coalesce(r.active_ids, 0) AS active_ids,
      coalesce(n.new_ids, 0) AS new_ids,
      0::bigint AS accepts,
      0::bigint AS navigations,
      0::bigint AS rerolls,
      coalesce(f.feedbacks, 0) AS feedbacks,
      coalesce(f.dislikes, 0) AS dislikes
    FROM days d
    LEFT JOIN recommendation_daily r USING (metric_date)
    LEFT JOIN new_id_daily n USING (metric_date)
    LEFT JOIN feedback_daily f USING (metric_date)
    ORDER BY d.metric_date
    """,
    366,
)

DAILY_V6 = QuerySpec(
    "daily_v6",
    """
    WITH bounds AS (
      SELECT CURRENT_DATE - (%s::integer - 1) AS start_date
    ), days AS (
      SELECT generate_series(
        (SELECT start_date FROM bounds), CURRENT_DATE, interval '1 day'
      )::date AS metric_date
    ), first_seen AS (
      SELECT anonymous_user_id, min(created_at)::date AS first_date
      FROM recommendation_log
      GROUP BY anonymous_user_id
    ), recommendation_daily AS (
      SELECT
        created_at::date AS metric_date,
        count(*) AS recommendations,
        count(DISTINCT anonymous_user_id) AS active_ids
      FROM recommendation_log, bounds
      WHERE created_at >= bounds.start_date
      GROUP BY created_at::date
    ), new_id_daily AS (
      SELECT first_date AS metric_date, count(*) AS new_ids
      FROM first_seen, bounds
      WHERE first_date >= bounds.start_date
      GROUP BY first_date
    ), behavior_daily AS (
      SELECT
        occurred_at::date AS metric_date,
        count(*) FILTER (WHERE behavior_type = 'ACCEPT') AS accepts,
        count(*) FILTER (WHERE behavior_type = 'NAVIGATE') AS navigations,
        count(*) FILTER (WHERE behavior_type = 'REROLL') AS rerolls
      FROM user_behavior, bounds
      WHERE occurred_at >= bounds.start_date
      GROUP BY occurred_at::date
    ), feedback_daily AS (
      SELECT
        created_at::date AS metric_date,
        count(*) AS feedbacks,
        count(*) FILTER (WHERE result = 'DISLIKE') AS dislikes
      FROM user_feedback, bounds
      WHERE created_at >= bounds.start_date
      GROUP BY created_at::date
    )
    SELECT
      d.metric_date,
      coalesce(r.recommendations, 0) AS recommendations,
      coalesce(r.active_ids, 0) AS active_ids,
      coalesce(n.new_ids, 0) AS new_ids,
      coalesce(b.accepts, 0) AS accepts,
      coalesce(b.navigations, 0) AS navigations,
      coalesce(b.rerolls, 0) AS rerolls,
      coalesce(f.feedbacks, 0) AS feedbacks,
      coalesce(f.dislikes, 0) AS dislikes
    FROM days d
    LEFT JOIN recommendation_daily r USING (metric_date)
    LEFT JOIN new_id_daily n USING (metric_date)
    LEFT JOIN behavior_daily b USING (metric_date)
    LEFT JOIN feedback_daily f USING (metric_date)
    ORDER BY d.metric_date
    """,
    366,
)

BEHAVIOR_DISTRIBUTION = QuerySpec(
    "behavior_distribution",
    """
    SELECT
      behavior_type AS label,
      count(*) AS event_count,
      count(DISTINCT anonymous_user_id) AS anonymous_ids
    FROM user_behavior
    WHERE occurred_at >= CURRENT_DATE - (%s::integer - 1)
    GROUP BY behavior_type
    ORDER BY event_count DESC, behavior_type
    """,
    32,
)

FEEDBACK_DISTRIBUTION = QuerySpec(
    "feedback_distribution",
    """
    SELECT
      result AS label,
      count(*) AS feedback_count,
      count(DISTINCT anonymous_user_id) AS anonymous_ids
    FROM user_feedback
    WHERE created_at >= CURRENT_DATE - (%s::integer - 1)
    GROUP BY result
    ORDER BY feedback_count DESC, result
    """,
    16,
)

RISK_DISTRIBUTION = QuerySpec(
    "risk_distribution",
    """
    SELECT
      CASE
        WHEN risk_score <= 20 THEN '[0,20]'
        WHEN risk_score <= 40 THEN '(20,40]'
        WHEN risk_score <= 60 THEN '(40,60]'
        ELSE '>60'
      END AS label,
      count(*) AS recommendation_count,
      round(avg(risk_score)::numeric, 2) AS average_score
    FROM recommendation_log
    WHERE created_at >= CURRENT_DATE - (%s::integer - 1)
    GROUP BY label
    ORDER BY min(risk_score)
    """,
    8,
)

RISK_CALIBRATION = QuerySpec(
    "risk_calibration",
    """
    SELECT
      risk_algorithm_version,
      risk_bucket,
      feedback_count,
      dislike_count,
      dislike_rate
    FROM v_risk_calibration
    ORDER BY risk_algorithm_version, risk_bucket
    """,
    100,
)

CATEGORY_DISTRIBUTION = QuerySpec(
    "category_distribution",
    """
    SELECT
      r.category_label AS label,
      count(*) AS candidate_count,
      count(*) FILTER (WHERE rc.shown) AS shown_count,
      count(DISTINCT rc.recommendation_log_id) AS session_count
    FROM recommendation_candidate rc
    JOIN recommendation_log rl ON rl.id = rc.recommendation_log_id
    JOIN restaurant r ON r.id = rc.restaurant_id
    WHERE rl.created_at >= CURRENT_DATE - (%s::integer - 1)
    GROUP BY r.category_label
    ORDER BY candidate_count DESC, r.category_label
    LIMIT %s
    """,
    20,
)

ALGORITHM_DISTRIBUTION_V1 = QuerySpec(
    "algorithm_distribution_v1",
    """
    SELECT
      recommendation_algorithm_version,
      risk_algorithm_version,
      'V1-V5'::text AS taste_algorithm_version,
      'DEFAULT'::text AS selection_mode,
      count(*) AS recommendation_count
    FROM recommendation_log
    WHERE created_at >= CURRENT_DATE - (%s::integer - 1)
    GROUP BY recommendation_algorithm_version, risk_algorithm_version
    ORDER BY recommendation_count DESC,
             recommendation_algorithm_version, risk_algorithm_version
    LIMIT 20
    """,
    20,
)

ALGORITHM_DISTRIBUTION_V6 = QuerySpec(
    "algorithm_distribution_v6",
    """
    SELECT
      recommendation_algorithm_version,
      risk_algorithm_version,
      taste_algorithm_version,
      selection_mode,
      count(*) AS recommendation_count
    FROM recommendation_log
    WHERE created_at >= CURRENT_DATE - (%s::integer - 1)
    GROUP BY recommendation_algorithm_version, risk_algorithm_version,
             taste_algorithm_version, selection_mode
    ORDER BY recommendation_count DESC,
             recommendation_algorithm_version, risk_algorithm_version,
             taste_algorithm_version, selection_mode
    LIMIT 20
    """,
    20,
)

LOCATION_GRIDS = QuerySpec(
    "location_grids",
    """
    WITH raw_location AS (
      SELECT
        anonymous_user_id,
        created_at,
        CASE
          WHEN jsonb_typeof(request_condition_json -> 'latitude') = 'number'
          THEN (request_condition_json ->> 'latitude')::numeric
        END AS latitude,
        CASE
          WHEN jsonb_typeof(request_condition_json -> 'longitude') = 'number'
          THEN (request_condition_json ->> 'longitude')::numeric
        END AS longitude
      FROM recommendation_log
    ), valid_location AS (
      SELECT
        anonymous_user_id,
        created_at,
        round(latitude, 1) AS lat_grid,
        round(longitude, 1) AS lon_grid
      FROM raw_location
      WHERE latitude BETWEEN -90 AND 90
        AND longitude BETWEEN -180 AND 180
    ), grid_activity AS (
      SELECT
        lat_grid,
        lon_grid,
        count(*) AS requests,
        count(DISTINCT anonymous_user_id) AS active_ids
      FROM valid_location
      GROUP BY lat_grid, lon_grid
    ), user_grid AS (
      SELECT
        anonymous_user_id,
        lat_grid,
        lon_grid,
        count(*) AS visits,
        max(created_at) AS last_seen
      FROM valid_location
      GROUP BY anonymous_user_id, lat_grid, lon_grid
    ), ranked_primary AS (
      SELECT
        anonymous_user_id,
        lat_grid,
        lon_grid,
        row_number() OVER (
          PARTITION BY anonymous_user_id
          ORDER BY visits DESC, last_seen DESC, lat_grid, lon_grid
        ) AS position
      FROM user_grid
    ), primary_users AS (
      SELECT lat_grid, lon_grid, count(*) AS primary_ids
      FROM ranked_primary
      WHERE position = 1
      GROUP BY lat_grid, lon_grid
    ), grids AS (
      SELECT
        a.lat_grid AS latitude,
        a.lon_grid AS longitude,
        a.requests,
        a.active_ids,
        coalesce(p.primary_ids, 0) AS primary_ids
      FROM grid_activity a
      LEFT JOIN primary_users p USING (lat_grid, lon_grid)
    ), ranked AS (
      SELECT
        latitude,
        longitude,
        requests,
        active_ids,
        primary_ids,
        row_number() OVER (
          ORDER BY primary_ids DESC, requests DESC, latitude, longitude
        ) AS grid_rank
      FROM grids
    ), visible AS (
      SELECT
        latitude,
        longitude,
        requests,
        active_ids,
        primary_ids,
        grid_rank
      FROM ranked
      WHERE grid_rank <= %s::integer
        AND greatest(primary_ids, active_ids) >= %s::integer
    ), hidden AS (
      SELECT
        count(*) AS hidden_grid_count,
        coalesce(sum(requests), 0) AS hidden_requests,
        coalesce(sum(active_ids), 0) AS hidden_active_id_entries,
        coalesce(sum(primary_ids), 0) AS hidden_primary_ids
      FROM ranked
      WHERE NOT (
        grid_rank <= %s::integer
        AND greatest(primary_ids, active_ids) >= %s::integer
      )
    )
    SELECT
      'POINT'::text AS row_type,
      latitude,
      longitude,
      requests,
      active_ids,
      primary_ids,
      0::bigint AS hidden_grid_count,
      0::numeric AS hidden_requests,
      0::numeric AS hidden_active_id_entries,
      0::numeric AS hidden_primary_ids
    FROM visible
    UNION ALL
    SELECT
      'HIDDEN'::text,
      NULL::numeric,
      NULL::numeric,
      0::bigint,
      0::bigint,
      0::bigint,
      hidden_grid_count,
      hidden_requests,
      hidden_active_id_entries,
      hidden_primary_ids
    FROM hidden
    ORDER BY row_type DESC, primary_ids DESC, requests DESC
    """,
    201,
)

SHADOW_SUMMARY = QuerySpec(
    "shadow_summary",
    """
    WITH period_sessions AS (
      SELECT id
      FROM recommendation_log
      WHERE created_at >= CURRENT_DATE - (%s::integer - 1)
    ), period_snapshots AS (
      SELECT s.id, s.recommendation_log_id, s.experiment_key, s.variant,
             s.all_candidates_json
      FROM recommendation_decision_snapshot s
      JOIN period_sessions ps ON ps.id = s.recommendation_log_id
    ), expanded AS (
      SELECT
        s.id,
        s.recommendation_log_id,
        s.experiment_key,
        s.variant,
        s.all_candidates_json ->> 'shadowDecisionStatus' AS decision_status,
        candidate.value AS candidate
      FROM period_snapshots s
      LEFT JOIN LATERAL jsonb_array_elements(
        s.all_candidates_json -> 'candidates'
      ) AS candidate(value) ON true
    ), per_snapshot AS (
      SELECT
        id,
        recommendation_log_id,
        experiment_key,
        variant,
        max(decision_status) AS decision_status,
        max(candidate ->> 'candidateId') FILTER (
          WHERE jsonb_typeof(candidate -> 'servedSlot') = 'number'
            AND (candidate ->> 'servedSlot')::integer = 1
        ) AS served_choice,
        max(candidate ->> 'candidateId') FILTER (
          WHERE jsonb_typeof(candidate -> 'shadowActual' -> 'slot') = 'number'
            AND (candidate -> 'shadowActual' ->> 'slot')::integer = 1
        ) AS shadow_choice
      FROM expanded
      GROUP BY id, recommendation_log_id, experiment_key, variant
    )
    SELECT
      (SELECT count(*) FROM period_sessions) AS period_recommendations,
      count(*) AS total_snapshots,
      count(DISTINCT recommendation_log_id) AS covered_recommendations,
      count(*) FILTER (WHERE decision_status = 'NO_SELECTABLE') AS no_selectable,
      count(*) FILTER (
        WHERE served_choice IS NOT NULL
          AND shadow_choice IS NOT NULL
          AND served_choice = shadow_choice
      ) AS same_first_choice,
      count(*) FILTER (
        WHERE served_choice IS NOT NULL
          AND shadow_choice IS NOT NULL
          AND served_choice <> shadow_choice
      ) AS changed_first_choice,
      round(
        count(DISTINCT recommendation_log_id)::numeric
        / nullif((SELECT count(*) FROM period_sessions), 0), 4
      ) AS coverage_rate
    FROM per_snapshot
    """,
    1,
)

SHADOW_VARIANTS = QuerySpec(
    "shadow_variants",
    """
    SELECT
      experiment_key,
      variant,
      served_risk_algorithm_version,
      shadow_risk_algorithm_version,
      served_recommendation_algorithm_version,
      shadow_recommendation_algorithm_version,
      count(*) AS snapshot_count
    FROM recommendation_decision_snapshot
    WHERE created_at >= CURRENT_DATE - (%s::integer - 1)
    GROUP BY experiment_key, variant,
             served_risk_algorithm_version, shadow_risk_algorithm_version,
             served_recommendation_algorithm_version,
             shadow_recommendation_algorithm_version
    ORDER BY snapshot_count DESC, experiment_key, variant
    LIMIT 20
    """,
    20,
)

SHADOW_SELECTION_REASONS = QuerySpec(
    "shadow_selection_reasons",
    """
    SELECT
      candidate.value -> 'shadowActual' ->> 'selectionReason' AS label,
      count(*) AS selection_count
    FROM recommendation_decision_snapshot s
    CROSS JOIN LATERAL jsonb_array_elements(
      s.all_candidates_json -> 'candidates'
    ) AS candidate(value)
    WHERE s.created_at >= CURRENT_DATE - (%s::integer - 1)
      AND jsonb_typeof(candidate.value -> 'shadowActual' -> 'slot') = 'number'
      AND (candidate.value -> 'shadowActual' ->> 'slot')::integer = 1
    GROUP BY label
    ORDER BY selection_count DESC, label
    LIMIT 20
    """,
    20,
)

TABLE_COUNTS_V1 = QuerySpec(
    "table_counts_v1",
    """
    SELECT 'restaurant'::text AS table_name, count(*) AS row_count FROM restaurant
    UNION ALL
    SELECT 'risk_result', count(*) FROM risk_result
    UNION ALL
    SELECT 'recommendation_log', count(*) FROM recommendation_log
    UNION ALL
    SELECT 'recommendation_candidate', count(*) FROM recommendation_candidate
    UNION ALL
    SELECT 'user_feedback', count(*) FROM user_feedback
    UNION ALL
    SELECT 'user_preference', count(*) FROM user_preference
    """,
    6,
)

TABLE_COUNTS_V3 = QuerySpec(
    "table_counts_v3",
    "SELECT 'external_entity_mapping'::text AS table_name, count(*) AS row_count FROM external_entity_mapping",
    1,
)

TABLE_COUNTS_V4 = QuerySpec(
    "table_counts_v4",
    """
    SELECT 'restaurant_deep_evidence'::text AS table_name, count(*) AS row_count FROM restaurant_deep_evidence
    UNION ALL
    SELECT 'restaurant_deep_analysis', count(*) FROM restaurant_deep_analysis
    """,
    2,
)

TABLE_COUNTS_V6 = QuerySpec(
    "table_counts_v6",
    """
    SELECT 'user_taste_profile'::text AS table_name, count(*) AS row_count FROM user_taste_profile
    UNION ALL
    SELECT 'user_behavior', count(*) FROM user_behavior
    UNION ALL
    SELECT 'user_food_history', count(*) FROM user_food_history
    UNION ALL
    SELECT 'restaurant_flavor_observation', count(*) FROM restaurant_flavor_observation
    """,
    4,
)

TABLE_COUNTS_V9 = QuerySpec(
    "table_counts_v9",
    "SELECT 'recommendation_decision_snapshot'::text AS table_name, count(*) AS row_count FROM recommendation_decision_snapshot",
    1,
)


ALL_QUERY_SPECS = (
    CAPABILITIES,
    CONNECTION_META,
    FLYWAY_VERSION,
    OVERVIEW,
    FUNNEL_V1,
    FUNNEL_V6,
    DAILY_V1,
    DAILY_V6,
    BEHAVIOR_DISTRIBUTION,
    FEEDBACK_DISTRIBUTION,
    RISK_DISTRIBUTION,
    RISK_CALIBRATION,
    CATEGORY_DISTRIBUTION,
    ALGORITHM_DISTRIBUTION_V1,
    ALGORITHM_DISTRIBUTION_V6,
    LOCATION_GRIDS,
    SHADOW_SUMMARY,
    SHADOW_VARIANTS,
    SHADOW_SELECTION_REASONS,
    TABLE_COUNTS_V1,
    TABLE_COUNTS_V3,
    TABLE_COUNTS_V4,
    TABLE_COUNTS_V6,
    TABLE_COUNTS_V9,
)


_FORBIDDEN_STATEMENT = re.compile(
    r"\b(?:INSERT|UPDATE|DELETE|MERGE|COPY|CALL|ALTER|CREATE|DROP|TRUNCATE|"
    r"GRANT|REVOKE|VACUUM|ANALYZE|REFRESH|REINDEX|CLUSTER|DO)\b",
    re.IGNORECASE,
)
_SELECT_STAR = re.compile(r"\bSELECT\s+(?:DISTINCT\s+)?(?:[A-Z_][A-Z0-9_$]*\s*\.\s*)?\*", re.IGNORECASE)
_QUALIFIED_STAR = re.compile(r"\b[A-Z_][A-Z0-9_$]*\s*\.\s*\*", re.IGNORECASE)


def _scrub_sql(sql: str) -> str:
    without_comments = re.sub(r"--[^\n]*|/\*.*?\*/", " ", sql, flags=re.DOTALL)
    return re.sub(r"'(?:''|[^'])*'", "''", without_comments)


def validate_read_only_query(spec: QuerySpec) -> None:
    """Reject query definitions that could return raw rows or mutate PostgreSQL."""
    scrubbed = _scrub_sql(spec.sql).strip()
    if _FORBIDDEN_STATEMENT.search(scrubbed):
        raise ValueError(f"{spec.name}: mutating or administrative SQL is forbidden")
    first_word = re.match(r"[A-Z]+", scrubbed, flags=re.IGNORECASE)
    if not first_word or first_word.group(0).upper() not in {"SELECT", "WITH", "SHOW"}:
        raise ValueError(f"{spec.name}: query must start with SELECT, WITH, or SHOW")
    if _SELECT_STAR.search(scrubbed) or _QUALIFIED_STAR.search(scrubbed):
        raise ValueError(f"{spec.name}: SELECT * is forbidden")
    if ";" in scrubbed.rstrip(";"):
        raise ValueError(f"{spec.name}: multiple SQL statements are forbidden")
    if spec.max_rows < 1:
        raise ValueError(f"{spec.name}: max_rows must be positive")


def validate_registry() -> None:
    names: set[str] = set()
    for spec in ALL_QUERY_SPECS:
        if spec.name in names:
            raise ValueError(f"duplicate query name: {spec.name}")
        names.add(spec.name)
        validate_read_only_query(spec)
