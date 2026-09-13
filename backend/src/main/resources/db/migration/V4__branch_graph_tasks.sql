CREATE TABLE index_job_branch_targets (
    job_id UUID PRIMARY KEY REFERENCES index_jobs(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    FOREIGN KEY(repo_id,branch_id,snapshot_id) REFERENCES branch_snapshots(repo_id,branch_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_branch_graph_target_snapshot ON index_job_branch_targets(repo_id,snapshot_id);
