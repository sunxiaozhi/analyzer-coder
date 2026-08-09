ALTER TABLE qa_sessions ADD COLUMN account_id UUID REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE qa_sessions ADD COLUMN repository_ids UUID[] NOT NULL DEFAULT '{}';
ALTER TABLE qa_sessions ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE qa_messages ADD COLUMN conversation_id UUID REFERENCES qa_conversations(id) ON DELETE SET NULL;
ALTER TABLE qa_citations ADD COLUMN repository_id UUID REFERENCES repositories(id) ON DELETE SET NULL;
CREATE INDEX idx_qa_sessions_account_updated ON qa_sessions(account_id,updated_at DESC);
