ALTER TABLE recommendation_log
    ADD COLUMN random_seed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN selection_snapshot_json JSONB NOT NULL DEFAULT '[]'::jsonb;
