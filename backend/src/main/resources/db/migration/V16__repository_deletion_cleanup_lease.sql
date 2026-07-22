ALTER TABLE repository_deletion_tombstones
  ADD COLUMN IF NOT EXISTS cleanup_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_repository_deletion_cleanup_queue
  ON repository_deletion_tombstones(cleanup_status, cleanup_updated_at, deleted_at);
