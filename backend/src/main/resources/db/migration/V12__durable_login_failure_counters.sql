CREATE TABLE login_failure_counters (
 username_normalized TEXT PRIMARY KEY, failure_count INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
