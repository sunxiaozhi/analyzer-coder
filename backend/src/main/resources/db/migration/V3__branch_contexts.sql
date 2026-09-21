-- Branch is the only code and knowledge scope. content_version is an opaque publication
-- token used to reject stale work; it is not an addressable historical source object.
CREATE TABLE repository_branches (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    content_version UUID,
    previous_content_version UUID,
    commit_sha TEXT,
    content_path TEXT,
    published_at TIMESTAMPTZ,
    generation BIGINT NOT NULL DEFAULT 0,
    preparation_status TEXT NOT NULL DEFAULT 'PENDING' CHECK(preparation_status IN ('PENDING','BUILDING','READY','FAILED')),
    preparation_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(repo_id,name), UNIQUE(repo_id,id)
);
INSERT INTO repository_branches(id,repo_id,name)
SELECT md5(id::text || ':branch:' || COALESCE(default_branch,'WORKSPACE'))::uuid,id,COALESCE(default_branch,'WORKSPACE')
FROM repositories WHERE deleted_at IS NULL;

ALTER TABLE code_chunks ADD COLUMN branch_id UUID;
UPDATE code_chunks c SET branch_id=b.id
FROM repository_branches b JOIN repositories r ON r.id=b.repo_id
WHERE c.repo_id=b.repo_id AND b.name=COALESCE(r.default_branch,'WORKSPACE');
ALTER TABLE code_chunks ALTER COLUMN branch_id SET NOT NULL;
ALTER TABLE code_chunks ADD CONSTRAINT fk_code_chunk_branch
    FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE;
CREATE INDEX idx_code_chunks_branch_version
    ON code_chunks(repo_id,branch_id,content_version);

CREATE FUNCTION assign_code_chunk_branch() RETURNS trigger AS $$
BEGIN
    IF NEW.branch_id IS NULL THEN
        SELECT b.id INTO NEW.branch_id FROM repository_branches b
        JOIN repositories r ON r.id=b.repo_id
        WHERE b.repo_id=NEW.repo_id
          AND (b.content_version=NEW.content_version
               OR b.previous_content_version=NEW.content_version
               OR b.name=COALESCE(r.default_branch,'WORKSPACE'))
        ORDER BY CASE WHEN b.content_version=NEW.content_version THEN 0
                      WHEN b.previous_content_version=NEW.content_version THEN 1 ELSE 2 END
        LIMIT 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_assign_code_chunk_branch BEFORE INSERT ON code_chunks
FOR EACH ROW EXECUTE FUNCTION assign_code_chunk_branch();

CREATE TABLE branch_read_contexts (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    content_version UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_branch_context_expiry ON branch_read_contexts(expires_at);

ALTER TABLE knowledge_cards ADD COLUMN branch_id UUID;
UPDATE knowledge_cards k SET branch_id=b.id
FROM repository_branches b JOIN repositories r ON r.id=b.repo_id
WHERE k.repo_id=b.repo_id AND b.name=COALESCE(r.default_branch,'WORKSPACE');
ALTER TABLE knowledge_cards ALTER COLUMN branch_id SET NOT NULL;
ALTER TABLE knowledge_cards ADD CONSTRAINT fk_knowledge_card_branch
    FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE;
CREATE INDEX idx_knowledge_cards_branch_status
    ON knowledge_cards(repo_id,branch_id,publication_status,updated_at DESC);

ALTER TABLE knowledge_card_revisions ADD COLUMN branch_id UUID;
UPDATE knowledge_card_revisions r SET branch_id=k.branch_id
FROM knowledge_cards k WHERE k.id=r.card_id;
ALTER TABLE knowledge_card_revisions ALTER COLUMN branch_id SET NOT NULL;
ALTER TABLE knowledge_card_revisions ADD CONSTRAINT fk_knowledge_revision_branch
    FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE;

-- V1 creates the revision trigger before branch ownership exists; replace it so every
-- historical knowledge revision preserves the same branch identity as the live card.
DROP TRIGGER trg_knowledge_card_revision ON knowledge_cards;
CREATE OR REPLACE FUNCTION capture_knowledge_card_revision() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(
        card_id,revision,repo_id,branch_id,title,card_type,content,tags,publication_status,
        knowledge_kind,severity,enforcement,owner_account_id,scope_payload,obligations_payload,
        last_verified_content_version,verification_note,changed_by,changed_at
    ) VALUES(
        NEW.id,NEW.revision,NEW.repo_id,NEW.branch_id,NEW.title,NEW.card_type,NEW.content,NEW.tags,
        NEW.publication_status,NEW.knowledge_kind,NEW.severity,NEW.enforcement,NEW.owner_account_id,
        NEW.scope_payload,NEW.obligations_payload,NEW.last_verified_content_version,
        NEW.verification_note,NEW.updated_by,NEW.updated_at
    )
    ON CONFLICT(card_id,revision) DO UPDATE SET
        branch_id=EXCLUDED.branch_id,title=EXCLUDED.title,card_type=EXCLUDED.card_type,
        content=EXCLUDED.content,tags=EXCLUDED.tags,publication_status=EXCLUDED.publication_status,
        knowledge_kind=EXCLUDED.knowledge_kind,severity=EXCLUDED.severity,
        enforcement=EXCLUDED.enforcement,owner_account_id=EXCLUDED.owner_account_id,
        scope_payload=EXCLUDED.scope_payload,obligations_payload=EXCLUDED.obligations_payload,
        last_verified_content_version=EXCLUDED.last_verified_content_version,
        verification_note=EXCLUDED.verification_note,changed_by=EXCLUDED.changed_by,
        changed_at=EXCLUDED.changed_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_card_revision
AFTER INSERT OR UPDATE OF title,card_type,content,tags,publication_status,knowledge_kind,
    severity,enforcement,owner_account_id,scope_payload,obligations_payload,
    last_verified_content_version,verification_note,revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();

CREATE TABLE knowledge_branch_validations (
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    branch_id UUID NOT NULL REFERENCES repository_branches(id) ON DELETE CASCADE,
    content_version UUID NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('CURRENT','UNVERIFIED','REVIEW_REQUIRED','INVALID')),
    note TEXT NOT NULL DEFAULT '',
    checked_by UUID REFERENCES accounts(id),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(card_id,revision) REFERENCES knowledge_card_revisions(card_id,revision) ON DELETE CASCADE,
    PRIMARY KEY(card_id,revision,branch_id,content_version)
);
CREATE TABLE branch_context_knowledge (
    context_id UUID NOT NULL REFERENCES branch_read_contexts(id) ON DELETE CASCADE,
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    PRIMARY KEY(context_id,card_id)
);

-- Existing repository cleanup ends by marking the tombstoned repository DELETED.
CREATE FUNCTION cleanup_deleted_repository_branches() RETURNS trigger AS $$
BEGIN
    DELETE FROM repository_branches WHERE repo_id=NEW.id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_cleanup_deleted_repository_branches AFTER UPDATE OF repository_status ON repositories
FOR EACH ROW WHEN (NEW.repository_status='DELETED' AND OLD.repository_status IS DISTINCT FROM NEW.repository_status)
EXECUTE FUNCTION cleanup_deleted_repository_branches();
