-- Additive branch storage. Legacy repository pointers remain legacy-only during rollout.
CREATE TABLE repository_branches (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    published_snapshot_id UUID,
    generation BIGINT NOT NULL DEFAULT 0,
    preparation_status TEXT NOT NULL DEFAULT 'PENDING' CHECK(preparation_status IN ('PENDING','BUILDING','READY','FAILED')),
    preparation_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(repo_id,name), UNIQUE(repo_id,id)
);
INSERT INTO repository_branches(id,repo_id,name)
SELECT md5(id::text || ':branch:' || COALESCE(default_branch,'WORKSPACE'))::uuid,id,COALESCE(default_branch,'WORKSPACE')
FROM repositories WHERE deleted_at IS NULL;

CREATE TABLE branch_snapshots (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    branch_id UUID NOT NULL,
    commit_sha TEXT NOT NULL,
    content_path TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE,
    UNIQUE(repo_id,branch_id,id), UNIQUE(branch_id,id)
);
ALTER TABLE repository_branches ADD CONSTRAINT fk_branch_published_snapshot
FOREIGN KEY(repo_id,id,published_snapshot_id) REFERENCES branch_snapshots(repo_id,branch_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE branch_read_contexts (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(repo_id,branch_id,snapshot_id) REFERENCES branch_snapshots(repo_id,branch_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_branch_context_expiry ON branch_read_contexts(expires_at);

CREATE TABLE knowledge_branch_scopes (
    card_id UUID PRIMARY KEY REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    mode TEXT NOT NULL DEFAULT 'SELECTED_BRANCHES' CHECK(mode IN ('ALL_BRANCHES','SELECTED_BRANCHES')),
    branch_ids UUID[] NOT NULL DEFAULT '{}',
    CHECK((mode='ALL_BRANCHES' AND cardinality(branch_ids)=0) OR (mode='SELECTED_BRANCHES' AND cardinality(branch_ids)>0))
);
INSERT INTO knowledge_branch_scopes(card_id,repo_id,branch_ids)
SELECT k.id,k.repo_id,ARRAY[b.id] FROM knowledge_cards k JOIN repository_branches b
ON b.repo_id=k.repo_id JOIN repositories r ON r.id=k.repo_id AND b.name=COALESCE(r.default_branch,'WORKSPACE');

CREATE TABLE knowledge_branch_scope_history (
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    mode TEXT NOT NULL,
    branch_ids UUID[] NOT NULL,
    PRIMARY KEY(card_id,revision)
);
INSERT INTO knowledge_branch_scope_history SELECT s.card_id,k.revision,s.mode,s.branch_ids
FROM knowledge_branch_scopes s JOIN knowledge_cards k ON k.id=s.card_id;

CREATE TABLE knowledge_branch_validations (
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    branch_id UUID NOT NULL REFERENCES repository_branches(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL REFERENCES branch_snapshots(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK(state IN ('CURRENT','UNVERIFIED','REVIEW_REQUIRED','INVALID')),
    note TEXT NOT NULL DEFAULT '',
    checked_by UUID REFERENCES accounts(id),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(branch_id,snapshot_id) REFERENCES branch_snapshots(branch_id,id) ON DELETE CASCADE,
    FOREIGN KEY(card_id,revision) REFERENCES knowledge_card_revisions(card_id,revision) ON DELETE CASCADE,
    PRIMARY KEY(card_id,revision,branch_id,snapshot_id)
);
CREATE TABLE branch_context_knowledge (
    context_id UUID NOT NULL REFERENCES branch_read_contexts(id) ON DELETE CASCADE,
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    PRIMARY KEY(context_id,card_id)
);

CREATE FUNCTION capture_branch_scope() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_branch_scope_history(card_id,revision,mode,branch_ids)
    SELECT NEW.id,NEW.revision,mode,branch_ids FROM knowledge_branch_scopes WHERE card_id=NEW.id
    ON CONFLICT(card_id,revision) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_capture_branch_scope AFTER UPDATE OF revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_branch_scope();

CREATE FUNCTION initialize_branch_scope() RETURNS trigger AS $$
DECLARE branch_uuid UUID;
BEGIN
    SELECT md5(id::text || ':branch:' || COALESCE(default_branch,'WORKSPACE'))::uuid INTO branch_uuid FROM repositories WHERE id=NEW.repo_id;
    INSERT INTO repository_branches(id,repo_id,name)
    SELECT branch_uuid,id,COALESCE(default_branch,'WORKSPACE') FROM repositories WHERE id=NEW.repo_id ON CONFLICT(repo_id,name) DO NOTHING;
    SELECT b.id INTO branch_uuid FROM repository_branches b JOIN repositories r ON r.id=b.repo_id
    WHERE r.id=NEW.repo_id AND b.name=COALESCE(r.default_branch,'WORKSPACE');
    INSERT INTO knowledge_branch_scopes(card_id,repo_id,branch_ids) VALUES(NEW.id,NEW.repo_id,ARRAY[branch_uuid]);
    INSERT INTO knowledge_branch_scope_history VALUES(NEW.id,NEW.revision,'SELECTED_BRANCHES',ARRAY[branch_uuid]);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_initialize_branch_scope AFTER INSERT ON knowledge_cards FOR EACH ROW EXECUTE FUNCTION initialize_branch_scope();

-- Legacy jobs must never delete or rebase chunks belonging to immutable branch snapshots.
-- The existing cleanup worker deletes chunks after tombstoning the repository.
CREATE FUNCTION protect_branch_chunk() RETURNS trigger AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM branch_snapshots WHERE id=OLD.snapshot_id)
       AND EXISTS(SELECT 1 FROM repositories WHERE id=OLD.repo_id AND deleted_at IS NULL) THEN
        RAISE EXCEPTION 'Immutable branch snapshot chunks cannot be rewritten or deleted';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_protect_branch_chunk BEFORE DELETE OR UPDATE ON code_chunks
FOR EACH ROW EXECUTE FUNCTION protect_branch_chunk();

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
