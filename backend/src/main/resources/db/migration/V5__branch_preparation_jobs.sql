CREATE TABLE branch_preparation_jobs (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    status TEXT NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    stage TEXT NOT NULL DEFAULT 'QUEUED',
    attempt_token UUID,
    target_commit TEXT,
    kind TEXT NOT NULL DEFAULT 'SNAPSHOT' CHECK (kind IN ('SNAPSHOT','VECTORS')),
    target_snapshot UUID,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE,
    FOREIGN KEY(branch_id,target_snapshot) REFERENCES branch_snapshots(branch_id,id) ON DELETE CASCADE,
    CHECK ((kind='SNAPSHOT' AND target_snapshot IS NULL) OR (kind='VECTORS' AND target_snapshot IS NOT NULL))
);
CREATE UNIQUE INDEX branch_preparation_one_active ON branch_preparation_jobs(branch_id,kind)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX branch_preparation_queue ON branch_preparation_jobs(status,created_at);
