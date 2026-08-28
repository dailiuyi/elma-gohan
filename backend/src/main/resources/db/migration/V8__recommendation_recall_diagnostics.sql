ALTER TABLE recommendation_log
    ADD COLUMN recall_diagnostics_json JSONB NOT NULL DEFAULT '{}'::jsonb;
