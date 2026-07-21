DROP INDEX IF EXISTS uq_index_jobs_one_active_per_repository;

CREATE UNIQUE INDEX uq_index_jobs_one_active_per_repository
  ON index_jobs (repo_id)
  WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');
