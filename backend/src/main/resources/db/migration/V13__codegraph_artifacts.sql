CREATE TABLE codegraph_artifacts (
 id UUID PRIMARY KEY, repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
 snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id) ON DELETE CASCADE,
 cli_version VARCHAR(40) NOT NULL, status VARCHAR(30) NOT NULL,
 artifact_path TEXT NOT NULL, node_count INTEGER, edge_count INTEGER,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, published_at TIMESTAMPTZ
);
CREATE INDEX idx_codegraph_artifacts_repo_snapshot ON codegraph_artifacts(repo_id,snapshot_id,created_at DESC);
