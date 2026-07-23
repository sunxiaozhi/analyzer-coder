CREATE TABLE knowledge_attachments (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    storage_path TEXT NOT NULL,
    uploaded_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    scan_status VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (repo_id, id)
);

CREATE TABLE knowledge_card_attachment_refs (
    card_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    attachment_id UUID NOT NULL REFERENCES knowledge_attachments(id) ON DELETE RESTRICT,
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (card_id, revision, attachment_id),
    FOREIGN KEY (card_id, revision)
        REFERENCES knowledge_card_revisions(card_id, revision) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_attachments_repo_created
    ON knowledge_attachments(repo_id, created_at DESC);
