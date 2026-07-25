CREATE TABLE knowledge_card_embeddings (
  card_id UUID PRIMARY KEY REFERENCES knowledge_cards(id) ON DELETE CASCADE,
  repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL,
  embedding vector(64) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_card_embeddings_repo ON knowledge_card_embeddings(repo_id);

CREATE TABLE knowledge_code_refs (
  card_id UUID NOT NULL,
  revision INTEGER NOT NULL,
  position INTEGER NOT NULL,
  repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  snapshot_id UUID REFERENCES repository_snapshots(id) ON DELETE SET NULL,
  chunk_id UUID REFERENCES code_chunks(id) ON DELETE SET NULL,
  file_path TEXT NOT NULL,
  symbol_name TEXT,
  start_line INTEGER,
  end_line INTEGER,
  content_hash VARCHAR(64) NOT NULL,
  PRIMARY KEY(card_id, revision, position),
  FOREIGN KEY(card_id, revision)
    REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_code_refs_card ON knowledge_code_refs(card_id, revision);
CREATE INDEX idx_knowledge_code_refs_repo_file ON knowledge_code_refs(repo_id, file_path);

ALTER TABLE qa_citations ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'CODE';
ALTER TABLE qa_citations ADD COLUMN knowledge_card_id UUID REFERENCES knowledge_cards(id) ON DELETE SET NULL;
ALTER TABLE qa_citations ADD COLUMN title TEXT;
