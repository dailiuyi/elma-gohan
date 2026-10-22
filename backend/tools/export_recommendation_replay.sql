-- Read-only export. Exploration rate comes from the saved request condition.
-- psql variables: recommendation_id, shadow_configuration (JSON).
-- Historical rows without servedExplorationRate cannot be exported by this query.
SELECT jsonb_build_object(
  'randomSeed', l.random_seed,
  'servedPoolSize', l.candidate_count,
  'servedExplorationRate', l.request_condition_json->'servedExplorationRate',
  'selectionSnapshot', l.selection_snapshot_json,
  'servedPool', (
    SELECT jsonb_agg(jsonb_build_object('source', r.source, 'sourcePoiId', r.source_poi_id) ORDER BY c.slot)
    FROM recommendation_candidate c JOIN restaurant r ON r.id=c.restaurant_id
    WHERE c.recommendation_log_id=l.id
  ),
  'minBudget', l.request_condition_json->'minBudget',
  'maxBudget', l.request_condition_json->'maxBudget',
  'shadowConfiguration', :'shadow_configuration'::jsonb,
  'shadowPayload', s.all_candidates_json
)
FROM recommendation_log l
JOIN recommendation_decision_snapshot s ON s.recommendation_log_id=l.id
WHERE l.id=:'recommendation_id'::uuid
  AND l.recommendation_algorithm_version='recommendation-v0.4.1'
  AND s.shadow_recommendation_algorithm_version='recommendation-v0.5'
  AND s.feature_schema_version=2
  AND s.random_seed=l.random_seed
  AND l.request_condition_json ? 'servedExplorationRate'
  AND s.experiment_key='safe-regret-v0.5';
