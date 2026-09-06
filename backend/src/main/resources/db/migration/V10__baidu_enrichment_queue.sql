ALTER TABLE external_entity_mapping ADD COLUMN match_algorithm_version varchar(64);

-- One outbound lane shared by every instance using this database.
CREATE TABLE baidu_call_state (
    id integer PRIMARY KEY CHECK (id = 1),
    next_allowed_at timestamptz NOT NULL DEFAULT now(),
    blocked_until timestamptz NOT NULL DEFAULT now(),
    failure_reason varchar(40)
);
INSERT INTO baidu_call_state(id) VALUES (1);

CREATE TABLE baidu_query_cache (
    query_hash varchar(64) PRIMARY KEY,
    result_json jsonb NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE TABLE baidu_enrichment_task (
    task_key varchar(64) PRIMARY KEY,
    payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now() + interval '1 day',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX baidu_enrichment_ready ON baidu_enrichment_task(available_at);
