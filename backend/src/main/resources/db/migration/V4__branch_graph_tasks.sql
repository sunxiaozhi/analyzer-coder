CREATE TABLE index_job_branch_targets (
    job_id UUID PRIMARY KEY REFERENCES index_jobs(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    content_version UUID NOT NULL,
    FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_branch_graph_target_content_version ON index_job_branch_targets(repo_id,content_version);
