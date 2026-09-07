"""Fixed, bounded aggregate queries. Business timestamps are Shanghai local time.

Schema sources: backend/src/main/resources/db/migration/V1, V3, V4, V6, V9, V10.
No user identifier or raw request/evidence JSON leaves these SQL aggregates.
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "database-guide"))
from dashboard_queries import QuerySpec, validate_read_only_query  # noqa: E402

BOUNDS = "SELECT %s::date AS start_date, (%s::date + 1) AS end_date"

OVERVIEW = QuerySpec("console_overview", f"""
WITH bounds AS ({BOUNDS}), first_seen AS (
  SELECT anonymous_user_id, min(created_at) AS first_at
  FROM recommendation_log GROUP BY anonymous_user_id
), period AS (
  SELECT r.anonymous_user_id, r.candidate_count, f.first_at
  FROM recommendation_log r JOIN first_seen f USING (anonymous_user_id), bounds b
  WHERE r.created_at >= b.start_date AND r.created_at < b.end_date
)
SELECT (SELECT count(*) FROM recommendation_log) AS total_recommendations,
  (SELECT count(*) FROM first_seen) AS total_anonymous_ids,
  (SELECT count(*) FROM restaurant) AS total_restaurants,
  (SELECT count(*) FROM user_feedback) AS total_feedbacks,
  count(*) AS period_recommendations,
  count(DISTINCT anonymous_user_id) AS period_active_ids,
  count(DISTINCT anonymous_user_id) FILTER (
    WHERE first_at >= (SELECT start_date FROM bounds)) AS period_new_ids,
  count(DISTINCT anonymous_user_id) FILTER (
    WHERE first_at < (SELECT start_date FROM bounds)) AS period_returning_ids,
  round(avg(candidate_count)::numeric, 2) AS average_candidate_count
FROM period
""", 1)


def funnel(has_behavior: bool) -> QuerySpec:
    behavior = """SELECT ub.recommendation_log_id,
      bool_or(ub.behavior_type = 'ACCEPT') AS accepted,
      bool_or(ub.behavior_type = 'NAVIGATE') AS navigated
      FROM user_behavior ub JOIN period p ON p.id = ub.recommendation_log_id, bounds b
      WHERE ub.occurred_at < b.end_date
      GROUP BY ub.recommendation_log_id""" if has_behavior else """
      SELECT NULL::uuid AS recommendation_log_id, false AS accepted, false AS navigated WHERE false"""
    return QuerySpec("console_funnel", f"""
WITH bounds AS ({BOUNDS}), period AS (
  SELECT id FROM recommendation_log, bounds b
  WHERE created_at >= b.start_date AND created_at < b.end_date
), behavior AS ({behavior}), feedback AS (
  SELECT uf.recommendation_log_id, count(*) AS feedback_count,
    bool_or(result = 'DISLIKE') AS disliked
  FROM user_feedback uf JOIN period p ON p.id = uf.recommendation_log_id, bounds b
  WHERE uf.created_at < b.end_date GROUP BY uf.recommendation_log_id
)
SELECT count(p.id) AS recommendation_sessions,
  count(p.id) FILTER (WHERE coalesce(b.accepted, false)) AS accepted_sessions,
  count(p.id) FILTER (WHERE coalesce(b.navigated, false)) AS navigated_sessions,
  count(f.recommendation_log_id) AS feedback_sessions,
  coalesce(sum(f.feedback_count), 0) AS feedback_count,
  count(p.id) FILTER (WHERE coalesce(f.disliked, false)) AS disliked_sessions,
  count(p.id) FILTER (WHERE coalesce(b.accepted, false))::numeric / nullif(count(p.id), 0) AS acceptance_rate,
  count(p.id) FILTER (WHERE coalesce(b.navigated, false))::numeric / nullif(count(p.id), 0) AS navigation_rate,
  count(f.recommendation_log_id)::numeric / nullif(count(p.id), 0) AS feedback_rate
FROM period p LEFT JOIN behavior b ON b.recommendation_log_id = p.id
LEFT JOIN feedback f ON f.recommendation_log_id = p.id
""", 1)


def daily(has_behavior: bool) -> QuerySpec:
    behavior = """SELECT occurred_at::date AS metric_date,
      count(*) FILTER (WHERE behavior_type = 'ACCEPT') AS accepts,
      count(*) FILTER (WHERE behavior_type = 'NAVIGATE') AS navigations,
      count(*) FILTER (WHERE behavior_type = 'REROLL') AS rerolls
      FROM user_behavior, bounds b
      WHERE occurred_at >= b.start_date AND occurred_at < b.end_date
      GROUP BY occurred_at::date""" if has_behavior else """
      SELECT NULL::date AS metric_date, 0 AS accepts, 0 AS navigations, 0 AS rerolls WHERE false"""
    return QuerySpec("console_daily", f"""
WITH bounds AS ({BOUNDS}), days AS (
  SELECT generate_series(start_date, end_date - 1, interval '1 day')::date AS metric_date FROM bounds
), first_seen AS (
  SELECT anonymous_user_id, min(created_at)::date AS first_date
  FROM recommendation_log GROUP BY anonymous_user_id
), new_daily AS (
  SELECT first_date AS metric_date, count(*) AS new_ids FROM first_seen, bounds b
  WHERE first_date >= b.start_date AND first_date < b.end_date GROUP BY first_date
), requests AS (
  SELECT created_at::date AS metric_date, count(*) AS recommendations,
    count(DISTINCT anonymous_user_id) AS active_ids
  FROM recommendation_log, bounds b
  WHERE created_at >= b.start_date AND created_at < b.end_date GROUP BY created_at::date
), behavior AS ({behavior}), feedback AS (
  SELECT created_at::date AS metric_date, count(*) AS feedbacks,
    count(*) FILTER (WHERE result = 'DISLIKE') AS dislikes
  FROM user_feedback, bounds b
  WHERE created_at >= b.start_date AND created_at < b.end_date GROUP BY created_at::date
)
SELECT d.metric_date, coalesce(r.recommendations, 0) AS recommendations,
  coalesce(r.active_ids, 0) AS active_ids, coalesce(n.new_ids, 0) AS new_ids,
  coalesce(b.accepts, 0) AS accepts, coalesce(b.navigations, 0) AS navigations,
  coalesce(b.rerolls, 0) AS rerolls, coalesce(f.feedbacks, 0) AS feedbacks,
  coalesce(f.dislikes, 0) AS dislikes
FROM days d LEFT JOIN requests r USING (metric_date) LEFT JOIN new_daily n USING (metric_date)
LEFT JOIN behavior b USING (metric_date) LEFT JOIN feedback f USING (metric_date)
ORDER BY d.metric_date
""", 90)

FREQUENCY = QuerySpec("console_frequency", f"""
WITH bounds AS ({BOUNDS}), counts AS (
  SELECT anonymous_user_id, count(*) AS visits FROM recommendation_log, bounds b
  WHERE created_at >= b.start_date AND created_at < b.end_date GROUP BY anonymous_user_id
), buckets AS (
  SELECT CASE WHEN visits = 1 THEN 1 WHEN visits <= 3 THEN 2 WHEN visits <= 7 THEN 3 ELSE 4 END AS bucket
  FROM counts
)
SELECT bucket, count(*) AS users FROM buckets GROUP BY bucket ORDER BY bucket
""", 4)

HEATMAP = QuerySpec("console_heatmap", f"""
WITH bounds AS ({BOUNDS})
SELECT extract(isodow FROM created_at)::integer AS weekday,
  extract(hour FROM created_at)::integer AS hour, count(*) AS requests
FROM recommendation_log, bounds b
WHERE created_at >= b.start_date AND created_at < b.end_date
GROUP BY weekday, hour ORDER BY weekday, hour
""", 168)

RETENTION = QuerySpec("console_retention", f"""
WITH bounds AS ({BOUNDS}), first_seen AS (
  SELECT anonymous_user_id, min(created_at)::date AS cohort_date
  FROM recommendation_log GROUP BY anonymous_user_id
), cohorts AS (
  SELECT anonymous_user_id, cohort_date FROM first_seen, bounds b
  WHERE cohort_date >= b.start_date AND cohort_date < b.end_date
), active_days AS (
  SELECT DISTINCT anonymous_user_id, created_at::date AS active_date
  FROM recommendation_log, bounds b
  WHERE created_at >= b.start_date AND created_at < b.end_date
), per_user AS (
  SELECT c.anonymous_user_id, c.cohort_date,
    bool_or(a.active_date = c.cohort_date + 1) AS d1,
    bool_or(a.active_date = c.cohort_date + 7) AS d7,
    bool_or(a.active_date = c.cohort_date + 14) AS d14,
    bool_or(a.active_date = c.cohort_date + 30) AS d30
  FROM cohorts c LEFT JOIN active_days a USING (anonymous_user_id)
  GROUP BY c.anonymous_user_id, c.cohort_date
)
SELECT cohort_date, count(*) AS cohort_size,
  CASE WHEN cohort_date + 1 < least((SELECT end_date FROM bounds), CURRENT_DATE)
    THEN count(*) FILTER (WHERE d1)::numeric / count(*) END AS day1,
  CASE WHEN cohort_date + 7 < least((SELECT end_date FROM bounds), CURRENT_DATE)
    THEN count(*) FILTER (WHERE d7)::numeric / count(*) END AS day7,
  CASE WHEN cohort_date + 14 < least((SELECT end_date FROM bounds), CURRENT_DATE)
    THEN count(*) FILTER (WHERE d14)::numeric / count(*) END AS day14,
  CASE WHEN cohort_date + 30 < least((SELECT end_date FROM bounds), CURRENT_DATE)
    THEN count(*) FILTER (WHERE d30)::numeric / count(*) END AS day30
FROM per_user GROUP BY cohort_date ORDER BY cohort_date
""", 90)

LOCATIONS = QuerySpec("console_locations", f"""
WITH bounds AS ({BOUNDS}), segments AS (
  SELECT lat, lo, hi, code FROM jsonb_to_recordset(%s::jsonb)
    AS s(lat integer, lo integer, hi integer, code text)
), raw AS (
  SELECT anonymous_user_id, created_at,
    CASE WHEN jsonb_typeof(request_condition_json -> 'latitude') = 'number'
      THEN round((request_condition_json ->> 'latitude')::numeric, 1) * 10 END AS lat,
    CASE WHEN jsonb_typeof(request_condition_json -> 'longitude') = 'number'
      THEN round((request_condition_json ->> 'longitude')::numeric, 1) * 10 END AS lon
  FROM recommendation_log, bounds b
  WHERE created_at >= b.start_date AND created_at < b.end_date
), located AS (
  SELECT r.anonymous_user_id, r.created_at, coalesce(s.code, 'UNMAPPED') AS code
  FROM raw r LEFT JOIN segments s ON r.lat = s.lat AND r.lon BETWEEN s.lo AND s.hi
), per_city AS (
  SELECT code, count(*) AS requests FROM located GROUP BY code
), user_city AS (
  SELECT anonymous_user_id, code, count(*) AS visits, max(created_at) AS last_seen
  FROM located GROUP BY anonymous_user_id, code
), ranked AS (
  SELECT code, row_number() OVER (PARTITION BY anonymous_user_id
    ORDER BY visits DESC, last_seen DESC, code) AS position FROM user_city
), primary_city AS (
  SELECT code, count(*) AS anonymous_ids FROM ranked WHERE position = 1 GROUP BY code
)
SELECT p.code, p.requests, coalesce(c.anonymous_ids, 0) AS anonymous_ids
FROM per_city p LEFT JOIN primary_city c USING (code)
ORDER BY anonymous_ids DESC, requests DESC, p.code
""", 500)

BEHAVIORS = QuerySpec("console_behaviors", f"""
WITH bounds AS ({BOUNDS}) SELECT behavior_type, count(*) AS event_count,
  count(DISTINCT recommendation_log_id) AS session_count FROM user_behavior, bounds b
WHERE occurred_at >= b.start_date AND occurred_at < b.end_date
GROUP BY behavior_type ORDER BY event_count DESC, behavior_type LIMIT 32
""", 32)

FEEDBACK = QuerySpec("console_feedback", f"""
WITH bounds AS ({BOUNDS}) SELECT result, count(*) AS feedback_count FROM user_feedback, bounds b
WHERE created_at >= b.start_date AND created_at < b.end_date
GROUP BY result ORDER BY feedback_count DESC, result LIMIT 16
""", 16)

RISKS = QuerySpec("console_risks", f"""
WITH bounds AS ({BOUNDS}) SELECT CASE WHEN risk_score <= 20 THEN '[0,20]'
  WHEN risk_score <= 40 THEN '(20,40]' WHEN risk_score <= 60 THEN '(40,60]' ELSE '>60' END AS risk_level,
  count(*) AS recommendation_count FROM recommendation_log, bounds b
WHERE created_at >= b.start_date AND created_at < b.end_date GROUP BY risk_level ORDER BY min(risk_score)
""", 4)

CATEGORIES = QuerySpec("console_categories", f"""
WITH bounds AS ({BOUNDS}) SELECT r.category_label AS category, count(*) AS recommendation_count
FROM recommendation_log l JOIN restaurant r ON r.id = l.current_restaurant_id, bounds b
WHERE l.created_at >= b.start_date AND l.created_at < b.end_date
GROUP BY r.category_label ORDER BY recommendation_count DESC, r.category_label LIMIT 20
""", 20)


def algorithms(has_selection: bool) -> QuerySpec:
    selection = "selection_mode" if has_selection else "'DEFAULT'::text"
    return QuerySpec("console_algorithms", f"""
WITH bounds AS ({BOUNDS}) SELECT recommendation_algorithm_version AS algorithm_version,
  {selection} AS selection_mode, count(*) AS recommendation_count FROM recommendation_log, bounds b
WHERE created_at >= b.start_date AND created_at < b.end_date
GROUP BY recommendation_algorithm_version, {selection}
ORDER BY recommendation_count DESC, algorithm_version, selection_mode LIMIT 20
""", 20)

MAPPING_STATUSES = QuerySpec("console_mapping_statuses", """
SELECT CASE WHEN match_status = 'MATCHED' AND
  jsonb_typeof(v3_evidence_json -> 'overallRating') = 'number' THEN 'MATCHED_WITH_RATING'
  WHEN match_status = 'MATCHED' THEN 'MATCHED_WITHOUT_RATING' ELSE match_status END AS status,
  count(*) AS count FROM external_entity_mapping GROUP BY status ORDER BY status LIMIT 32
""", 32)
MAPPING_TOTALS = QuerySpec("console_mapping_totals", """
SELECT count(*) AS total_mappings, count(*) FILTER (WHERE match_status = 'MATCHED'
  AND jsonb_typeof(v3_evidence_json -> 'overallRating') = 'number') AS mappings_with_ratings,
  count(*) FILTER (WHERE expires_at > CURRENT_TIMESTAMP AT TIME ZONE 'UTC') AS fresh_mappings
FROM external_entity_mapping
""", 1)
DEEP_STATUSES = QuerySpec("console_deep_statuses", """
SELECT source, status, count(*) AS count FROM restaurant_deep_evidence
GROUP BY source, status ORDER BY source, status LIMIT 128
""", 128)


def scoped_shadow(spec: QuerySpec) -> QuerySpec:
    """Legacy shadow queries have one lower bound; add the matching upper bound."""
    import re
    sql = re.sub(r"((?:s\.)?created_at) >= CURRENT_DATE - \(%s::integer - 1\)",
                 r"\1 >= %s::date AND \1 < (%s::date + 1)", spec.sql)
    return QuerySpec("console_" + spec.name, sql, spec.max_rows)


ALL_QUERIES = (OVERVIEW, funnel(True), funnel(False), daily(True), daily(False),
               FREQUENCY, HEATMAP, RETENTION, LOCATIONS, BEHAVIORS, FEEDBACK,
               RISKS, CATEGORIES, algorithms(True), algorithms(False),
               MAPPING_STATUSES, MAPPING_TOTALS, DEEP_STATUSES)
for query in ALL_QUERIES:
    validate_read_only_query(query)
