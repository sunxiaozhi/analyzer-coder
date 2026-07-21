ALTER TABLE repositories
  ADD COLUMN IF NOT EXISTS current_snapshot_id UUID,
  ADD COLUMN IF NOT EXISTS current_snapshot_path TEXT,
  ADD COLUMN IF NOT EXISTS snapshot_created_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS repository_snapshots (
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  source_commit TEXT NOT NULL,
  worktree_digest TEXT NOT NULL,
  storage_path TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repository_snapshots_repo_created
  ON repository_snapshots (repo_id, created_at DESC);

ALTER TABLE code_chunks
  ADD COLUMN IF NOT EXISTS snapshot_id UUID;

CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_snapshot
  ON code_chunks (repo_id, snapshot_id);
