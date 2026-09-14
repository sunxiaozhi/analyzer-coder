-- Complete the project/branch rollout without rewriting V1-V6.

ALTER TABLE repository_branches
    ADD COLUMN tracking_status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (tracking_status IN ('ACTIVE','ARCHIVED')),
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN archived_by UUID REFERENCES accounts(id);
CREATE INDEX idx_repository_branches_active
    ON repository_branches(repo_id,name) WHERE tracking_status='ACTIVE';

-- Keep the legacy published default version addressable through the same branch context model.
-- This makes rollout non-disruptive and also seeds branches for repositories created after V3.
INSERT INTO repository_branches(id,repo_id,name)
SELECT md5(r.id::text || ':branch:' || COALESCE(r.default_branch,'WORKSPACE'))::uuid,
       r.id,COALESCE(r.default_branch,'WORKSPACE')
FROM repositories r
ON CONFLICT(repo_id,name) DO NOTHING;
INSERT INTO branch_snapshots(id,repo_id,branch_id,commit_sha,content_path)
SELECT r.current_snapshot_id,r.id,b.id,COALESCE(r.current_commit,r.worktree_digest,'WORKSPACE'),r.current_snapshot_path
FROM repositories r JOIN repository_branches b
  ON b.repo_id=r.id AND b.name=COALESCE(r.default_branch,'WORKSPACE')
WHERE r.current_snapshot_id IS NOT NULL AND r.current_snapshot_path IS NOT NULL
  AND NOT EXISTS(SELECT 1 FROM branch_snapshots existing WHERE existing.id=r.current_snapshot_id);
UPDATE repository_branches b SET
    published_snapshot_id=r.current_snapshot_id,
    preparation_status='READY',
    preparation_error=NULL,
    updated_at=CURRENT_TIMESTAMP
FROM repositories r
WHERE b.repo_id=r.id AND b.name=COALESCE(r.default_branch,'WORKSPACE')
  AND EXISTS(
      SELECT 1 FROM branch_snapshots s
      WHERE s.id=r.current_snapshot_id AND s.repo_id=r.id AND s.branch_id=b.id
  );

CREATE FUNCTION synchronize_default_repository_branch() RETURNS trigger AS $$
DECLARE branch_uuid UUID;
BEGIN
    branch_uuid := md5(NEW.id::text || ':branch:' || COALESCE(NEW.default_branch,'WORKSPACE'))::uuid;
    INSERT INTO repository_branches(id,repo_id,name)
    VALUES(branch_uuid,NEW.id,COALESCE(NEW.default_branch,'WORKSPACE'))
    ON CONFLICT(repo_id,name) DO NOTHING;
    SELECT id INTO branch_uuid FROM repository_branches
    WHERE repo_id=NEW.id AND name=COALESCE(NEW.default_branch,'WORKSPACE');
    IF NEW.current_snapshot_id IS NOT NULL AND NEW.current_snapshot_path IS NOT NULL
       AND EXISTS(SELECT 1 FROM repository_branches WHERE id=branch_uuid AND tracking_status='ACTIVE') THEN
        INSERT INTO branch_snapshots(id,repo_id,branch_id,commit_sha,content_path)
        VALUES(NEW.current_snapshot_id,NEW.id,branch_uuid,
               COALESCE(NEW.current_commit,NEW.worktree_digest,'WORKSPACE'),NEW.current_snapshot_path)
        ON CONFLICT(id) DO NOTHING;
        UPDATE repository_branches SET
            published_snapshot_id=NEW.current_snapshot_id,
            preparation_status='READY',preparation_error=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE id=branch_uuid AND repo_id=NEW.id
          AND EXISTS(SELECT 1 FROM branch_snapshots WHERE id=NEW.current_snapshot_id AND branch_id=branch_uuid);
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER repositories_default_branch_snapshot
AFTER INSERT OR UPDATE OF current_snapshot_id,current_snapshot_path,current_commit,default_branch
ON repositories FOR EACH ROW EXECUTE FUNCTION synchronize_default_repository_branch();

-- A question belongs to a branch, while snapshot_id keeps the exact historical version.
ALTER TABLE qa_conversations
    ADD COLUMN branch_id UUID,
    ADD COLUMN context_id UUID,
    ADD COLUMN branch_name TEXT,
    ADD COLUMN commit_sha TEXT;
UPDATE qa_conversations q SET
    branch_id=s.branch_id,
    branch_name=b.name,
    commit_sha=s.commit_sha
FROM branch_snapshots s JOIN repository_branches b ON b.id=s.branch_id
WHERE q.snapshot_id=s.id AND q.repo_id=s.repo_id;
ALTER TABLE qa_conversations
    ADD CONSTRAINT fk_qa_conversation_branch
        FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id),
    ADD CONSTRAINT fk_qa_conversation_context
        FOREIGN KEY(context_id) REFERENCES branch_read_contexts(id) ON DELETE SET NULL;
CREATE INDEX idx_qa_conversations_branch_history
    ON qa_conversations(account_id,repo_id,branch_id,updated_at DESC);

-- The same Markdown path is an independent source in each branch.
ALTER TABLE repository_markdown_sources ADD COLUMN branch_id UUID;
UPDATE repository_markdown_sources s SET branch_id=b.id
FROM repository_branches b JOIN repositories r ON r.id=b.repo_id
WHERE s.repo_id=b.repo_id AND b.name=COALESCE(r.default_branch,'WORKSPACE');
ALTER TABLE repository_markdown_sources ALTER COLUMN branch_id SET NOT NULL;
ALTER TABLE repository_markdown_sources DROP CONSTRAINT uq_repository_markdown_source_path;
ALTER TABLE repository_markdown_sources
    ADD CONSTRAINT uq_repository_markdown_source_branch_path UNIQUE(repo_id,branch_id,file_path),
    ADD CONSTRAINT fk_markdown_source_branch
        FOREIGN KEY(repo_id,branch_id) REFERENCES repository_branches(repo_id,id) ON DELETE CASCADE;
DROP INDEX idx_repository_markdown_sources_snapshot;
DROP INDEX idx_repository_markdown_sources_path_hash;
CREATE INDEX idx_repository_markdown_sources_snapshot
    ON repository_markdown_sources(repo_id,branch_id,snapshot_id,file_path);
CREATE INDEX idx_repository_markdown_sources_path_hash
    ON repository_markdown_sources(repo_id,branch_id,file_path,content_hash);

ALTER TABLE knowledge_card_markdown_source_links ADD COLUMN source_branch_id UUID;
UPDATE knowledge_card_markdown_source_links l SET source_branch_id=COALESCE(
    (SELECT s.branch_id FROM repository_markdown_sources s WHERE s.id=l.source_id),
    (SELECT b.id FROM repository_branches b JOIN repositories r ON r.id=b.repo_id
      WHERE b.repo_id=l.repo_id AND b.name=COALESCE(r.default_branch,'WORKSPACE') LIMIT 1)
);
ALTER TABLE knowledge_card_markdown_source_links ALTER COLUMN source_branch_id SET NOT NULL;
ALTER TABLE knowledge_card_markdown_source_links
    ADD CONSTRAINT fk_knowledge_markdown_source_branch
        FOREIGN KEY(repo_id,source_branch_id) REFERENCES repository_branches(repo_id,id);
DROP INDEX idx_knowledge_markdown_links_source;
CREATE INDEX idx_knowledge_markdown_links_source
    ON knowledge_card_markdown_source_links(repo_id,source_branch_id,source_path,generated_at DESC);

COMMENT ON COLUMN repository_branches.tracking_status IS
    '受管分支生命周期；归档只停止新上下文和新任务，不删除历史证据';
COMMENT ON COLUMN qa_conversations.branch_id IS
    '问答所属分支；旧的默认版本问答允许为空';
COMMENT ON COLUMN repository_markdown_sources.branch_id IS
    'Markdown 来源所属的稳定分支身份，同路径跨分支互不覆盖';
COMMENT ON COLUMN knowledge_card_markdown_source_links.source_branch_id IS
    '生成知识修订时 Markdown 来源所属分支';

-- Project metadata is persisted before source acquisition. A draft becomes linked to the
-- existing repository identity only after source validation/import succeeds.
CREATE TABLE repository_project_drafts (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    source_type TEXT,
    source_location TEXT,
    credential_id UUID REFERENCES git_credentials(id) ON DELETE SET NULL,
    lifecycle_status TEXT NOT NULL DEFAULT 'DRAFT'
        CHECK(lifecycle_status IN ('DRAFT','SOURCE_CONFIGURED','IMPORTING','READY','FAILED')),
    result_repository_id UUID REFERENCES repositories(id) ON DELETE SET NULL,
    error TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_repository_project_drafts_owner
    ON repository_project_drafts(owner_account_id,updated_at DESC);
ALTER TABLE repository_import_jobs
    ADD COLUMN project_draft_id UUID REFERENCES repository_project_drafts(id) ON DELETE SET NULL;
