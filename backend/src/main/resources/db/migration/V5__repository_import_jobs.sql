CREATE TABLE repository_import_jobs (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    credential_id UUID REFERENCES git_credentials(id) ON DELETE RESTRICT,
    source_type TEXT NOT NULL,
    repository_name TEXT NOT NULL,
    remote_url TEXT NOT NULL,
    branch TEXT,
    status TEXT NOT NULL DEFAULT 'QUEUED',
    current_step TEXT NOT NULL DEFAULT 'queued',
    error_message TEXT,
    result_repository_id UUID REFERENCES repositories(id) ON DELETE SET NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_repository_import_status CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELED'))
);
CREATE INDEX idx_repository_import_jobs_queue ON repository_import_jobs(status,created_at);
CREATE INDEX idx_repository_import_jobs_actor ON repository_import_jobs(account_id,created_at DESC);
