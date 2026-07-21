ALTER TABLE repositories
  ADD COLUMN IF NOT EXISTS source_type TEXT NOT NULL DEFAULT 'LOCAL_GIT',
  ADD COLUMN IF NOT EXISTS worktree_digest TEXT,
  ADD COLUMN IF NOT EXISTS worktree_dirty BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS last_scanned_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_repositories_name_normalized
  ON repositories (LOWER(BTRIM(name)));

CREATE UNIQUE INDEX IF NOT EXISTS uq_repositories_path
  ON repositories (path);
