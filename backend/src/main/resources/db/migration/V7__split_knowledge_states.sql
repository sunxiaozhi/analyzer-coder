DROP TRIGGER IF EXISTS trg_confirm_knowledge_code_version ON knowledge_cards;
DROP FUNCTION IF EXISTS confirm_knowledge_code_version();
DROP TRIGGER IF EXISTS trg_repository_knowledge_stale ON repositories;
DROP FUNCTION IF EXISTS mark_repository_knowledge_stale();
DROP TRIGGER IF EXISTS trg_knowledge_card_revision ON knowledge_cards;
DROP FUNCTION IF EXISTS capture_knowledge_card_revision();

ALTER TABLE knowledge_cards RENAME COLUMN status TO publication_status;
ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_code_review_status;
ALTER TABLE knowledge_cards RENAME COLUMN code_review_status TO source_version_status;
ALTER TABLE knowledge_cards RENAME COLUMN code_reviewed_at TO source_version_checked_at;
ALTER TABLE knowledge_cards ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'UNREVIEWED';
ALTER TABLE knowledge_cards ADD COLUMN reviewed_by UUID REFERENCES accounts(id) ON DELETE SET NULL;
ALTER TABLE knowledge_cards ADD COLUMN reviewed_at TIMESTAMPTZ;

UPDATE knowledge_cards
SET publication_status=CASE
        WHEN publication_status='PUBLISHED' THEN 'PUBLISHED'
        WHEN publication_status='ARCHIVED' THEN 'ARCHIVED'
        ELSE 'DRAFT'
    END,
    source_version_status=CASE
        WHEN source_version_status='CURRENT' THEN 'CURRENT'
        WHEN source_version_status='REVIEW_REQUIRED' THEN 'STALE'
        ELSE 'UNVERIFIED'
    END,
    review_status='UNREVIEWED',reviewed_by=NULL,reviewed_at=NULL;

ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_publication_status
    CHECK (publication_status IN ('DRAFT','PUBLISHED','ARCHIVED'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_source_version_status
    CHECK (source_version_status IN ('UNVERIFIED','CURRENT','STALE'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_review_status
    CHECK (review_status IN ('UNREVIEWED','APPROVED','CHANGES_REQUESTED'));

ALTER TABLE knowledge_card_revisions RENAME COLUMN status TO publication_status;

COMMENT ON COLUMN knowledge_cards.publication_status IS '发布状态：草稿、已发布或已归档';
COMMENT ON COLUMN knowledge_cards.source_version_status IS '来源版本状态：未验证、当前或已过期；不表示人工认可内容';
COMMENT ON COLUMN knowledge_cards.source_version_checked_at IS '最近一次自动核对来源版本的时间';
COMMENT ON COLUMN knowledge_cards.review_status IS '人工评审状态，与来源版本及发布状态独立';
COMMENT ON COLUMN knowledge_cards.reviewed_by IS '最近一次人工评审账号';
COMMENT ON COLUMN knowledge_cards.reviewed_at IS '最近一次人工评审时间';
COMMENT ON COLUMN knowledge_card_revisions.publication_status IS '该历史修订保存时的发布状态';

CREATE OR REPLACE FUNCTION capture_knowledge_card_revision() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(
        card_id,revision,repo_id,title,card_type,content,tags,publication_status,changed_by,changed_at
    ) VALUES(
        NEW.id,NEW.revision,NEW.repo_id,NEW.title,NEW.card_type,NEW.content,NEW.tags,
        NEW.publication_status,NEW.updated_by,NEW.updated_at
    )
    ON CONFLICT(card_id,revision) DO UPDATE SET
        title=EXCLUDED.title,card_type=EXCLUDED.card_type,content=EXCLUDED.content,
        tags=EXCLUDED.tags,publication_status=EXCLUDED.publication_status,
        changed_by=EXCLUDED.changed_by,changed_at=EXCLUDED.changed_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_knowledge_card_revision
AFTER INSERT OR UPDATE OF title,card_type,content,tags,publication_status,revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();

CREATE OR REPLACE FUNCTION mark_repository_knowledge_stale() RETURNS trigger AS $$
BEGIN
    IF OLD.current_commit IS DISTINCT FROM NEW.current_commit THEN
        UPDATE knowledge_cards
        SET source_version_status='STALE',source_version_checked_at=CURRENT_TIMESTAMP
        WHERE repo_id=NEW.id AND verified_commit IS NOT NULL
          AND verified_commit IS DISTINCT FROM NEW.current_commit;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_repository_knowledge_stale
AFTER UPDATE OF current_commit ON repositories
FOR EACH ROW EXECUTE FUNCTION mark_repository_knowledge_stale();
