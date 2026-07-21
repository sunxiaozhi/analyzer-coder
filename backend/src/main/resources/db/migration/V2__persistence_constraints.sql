CREATE UNIQUE INDEX IF NOT EXISTS uq_repositories_normalized_path
  ON repositories (path);

CREATE UNIQUE INDEX IF NOT EXISTS uq_index_jobs_one_active_per_repository
  ON index_jobs (repo_id)
  WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_created_at
  ON code_chunks (repo_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_language
  ON code_chunks (repo_id, language);

ALTER TABLE chunk_embeddings
  DROP CONSTRAINT IF EXISTS chunk_embeddings_chunk_id_fkey;

ALTER TABLE chunk_embeddings
  ADD CONSTRAINT chunk_embeddings_chunk_id_fkey
  FOREIGN KEY (chunk_id) REFERENCES code_chunks(id) ON DELETE CASCADE;
