ALTER TABLE knowledge_cards ADD COLUMN IF NOT EXISTS card_type VARCHAR(40) NOT NULL DEFAULT '模块说明';
ALTER TABLE knowledge_cards ADD COLUMN IF NOT EXISTS revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE knowledge_cards ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES accounts(id) ON DELETE SET NULL;
ALTER TABLE knowledge_cards ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL;
UPDATE knowledge_cards SET tags='{}' WHERE tags IS NULL;
ALTER TABLE knowledge_cards ALTER COLUMN tags SET DEFAULT '{}';
ALTER TABLE knowledge_cards ALTER COLUMN tags SET NOT NULL;
DROP INDEX IF EXISTS idx_knowledge_cards_repo_status;
CREATE INDEX idx_knowledge_cards_repo_status ON knowledge_cards(repo_id,status,updated_at DESC);

CREATE TABLE qa_conversations (
 id UUID PRIMARY KEY, repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
 account_id UUID REFERENCES accounts(id) ON DELETE SET NULL, question TEXT NOT NULL, answer TEXT NOT NULL,
 snapshot_id UUID REFERENCES repository_snapshots(id) ON DELETE SET NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE qa_citations (
 id UUID PRIMARY KEY, conversation_id UUID NOT NULL REFERENCES qa_conversations(id) ON DELETE CASCADE,
 chunk_id UUID REFERENCES code_chunks(id) ON DELETE SET NULL, file_path TEXT NOT NULL, symbol_name TEXT,
 start_line INTEGER, end_line INTEGER, evidence_hash VARCHAR(64) NOT NULL, rank INTEGER NOT NULL
);
CREATE TABLE system_settings (
 setting_key VARCHAR(120) PRIMARY KEY, setting_value TEXT NOT NULL, sensitive BOOLEAN NOT NULL DEFAULT FALSE,
 updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE backup_sets (
 id UUID PRIMARY KEY, status VARCHAR(30) NOT NULL, manifest JSONB NOT NULL, checksum VARCHAR(64) NOT NULL,
 created_by UUID REFERENCES accounts(id) ON DELETE SET NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, restored_at TIMESTAMPTZ
);

DROP TABLE chunk_embeddings;
CREATE TABLE chunk_embeddings (
 chunk_id UUID PRIMARY KEY REFERENCES code_chunks(id) ON DELETE CASCADE,
 repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
 model VARCHAR(100) NOT NULL, dimension INTEGER NOT NULL, embedding vector(64) NOT NULL,
 content_hash VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_chunk_embeddings_repo ON chunk_embeddings(repo_id);

CREATE TABLE code_graph_edges (
 id UUID PRIMARY KEY, repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
 snapshot_id UUID NOT NULL REFERENCES repository_snapshots(id) ON DELETE CASCADE,
 source_chunk_id UUID REFERENCES code_chunks(id) ON DELETE CASCADE, target_chunk_id UUID REFERENCES code_chunks(id) ON DELETE CASCADE,
 source_symbol TEXT NOT NULL, target_symbol TEXT NOT NULL, relation VARCHAR(30) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(repo_id,snapshot_id,source_chunk_id,target_chunk_id,relation)
);
CREATE INDEX idx_code_graph_edges_source ON code_graph_edges(repo_id,source_symbol);
CREATE INDEX idx_code_graph_edges_target ON code_graph_edges(repo_id,target_symbol);

INSERT INTO system_settings(setting_key,setting_value) VALUES
 ('externalModelEnabled','false'),('embeddingModel','local-hash-64'),('llmProvider','deterministic-local'),
 ('maxSearchResults','20'),('excludedPatterns','.env*,*.pem,*.key,credentials.*,secrets/**'),('backupRetentionDays','30')
ON CONFLICT(setting_key) DO NOTHING;
