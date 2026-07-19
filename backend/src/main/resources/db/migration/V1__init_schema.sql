CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS repositories (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  path TEXT NOT NULL,
  default_branch TEXT,
  current_commit TEXT,
  codegraph_path TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS index_jobs (
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id),
  job_type TEXT NOT NULL,
  status TEXT NOT NULL,
  current_step TEXT,
  error_message TEXT,
  started_at TIMESTAMP,
  finished_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS code_chunks (
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id),
  commit_sha TEXT NOT NULL,
  file_path TEXT NOT NULL,
  symbol_id TEXT,
  symbol_name TEXT,
  symbol_kind TEXT,
  language TEXT,
  chunk_type TEXT NOT NULL,
  start_line INT,
  end_line INT,
  content TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_file ON code_chunks(repo_id, file_path);
CREATE INDEX IF NOT EXISTS idx_code_chunks_repo_symbol ON code_chunks(repo_id, symbol_id);

CREATE TABLE IF NOT EXISTS chunk_embeddings (
  chunk_id UUID PRIMARY KEY REFERENCES code_chunks(id),
  embedding_model TEXT NOT NULL,
  embedding vector,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS qa_sessions (
  id UUID PRIMARY KEY,
  repo_id UUID REFERENCES repositories(id),
  title TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS qa_messages (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES qa_sessions(id),
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  citations JSONB,
  retrieval_trace JSONB,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_cards (
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id),
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  source_message_id UUID,
  tags TEXT[],
  status TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_index_jobs_repo_created_at ON index_jobs(repo_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_qa_sessions_repo_updated_at ON qa_sessions(repo_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_cards_repo_status ON knowledge_cards(repo_id, status);
