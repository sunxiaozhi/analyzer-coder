ALTER TABLE knowledge_cards
    ADD COLUMN knowledge_kind VARCHAR(40) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    ADD COLUMN enforcement VARCHAR(20) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN owner_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    ADD COLUMN scope_payload JSONB NOT NULL DEFAULT '{"pathPatterns":[],"symbols":[],"modules":[]}'::jsonb,
    ADD COLUMN obligations_payload JSONB NOT NULL DEFAULT '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[]}'::jsonb,
    ADD COLUMN last_verified_snapshot_id UUID,
    ADD COLUMN verification_note TEXT;

ALTER TABLE knowledge_card_revisions
    ADD COLUMN knowledge_kind VARCHAR(40) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    ADD COLUMN enforcement VARCHAR(20) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN owner_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    ADD COLUMN scope_payload JSONB NOT NULL DEFAULT '{"pathPatterns":[],"symbols":[],"modules":[]}'::jsonb,
    ADD COLUMN obligations_payload JSONB NOT NULL DEFAULT '{"requiredTests":[],"requiredApproverAccountIds":[],"instructions":[]}'::jsonb,
    ADD COLUMN last_verified_snapshot_id UUID,
    ADD COLUMN verification_note TEXT;

ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_source_version_status;
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_source_version_status
    CHECK (source_version_status IN ('UNVERIFIED','CURRENT','SUSPECT','STALE'));

ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_kind
    CHECK (knowledge_kind IN (
        'REFERENCE','BUSINESS_RULE','ARCH_DECISION','API_CONTRACT','DATA_CONSTRAINT',
        'TEST_OBLIGATION','SECURITY_POLICY','RUNBOOK','INCIDENT_LESSON','OWNERSHIP','TECH_DEBT'
    ));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_severity
    CHECK (severity IN ('INFO','WARNING','CRITICAL'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_enforcement
    CHECK (enforcement IN ('REFERENCE','ADVISORY','REQUIRED'));
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_scope_payload
    CHECK (
        jsonb_typeof(scope_payload)='object'
        AND jsonb_typeof(scope_payload->'pathPatterns')='array'
        AND jsonb_typeof(scope_payload->'symbols')='array'
        AND jsonb_typeof(scope_payload->'modules')='array'
    );
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_obligations_payload
    CHECK (
        jsonb_typeof(obligations_payload)='object'
        AND jsonb_typeof(obligations_payload->'requiredTests')='array'
        AND jsonb_typeof(obligations_payload->'requiredApproverAccountIds')='array'
        AND jsonb_typeof(obligations_payload->'instructions')='array'
    );

ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_kind
    CHECK (knowledge_kind IN (
        'REFERENCE','BUSINESS_RULE','ARCH_DECISION','API_CONTRACT','DATA_CONSTRAINT',
        'TEST_OBLIGATION','SECURITY_POLICY','RUNBOOK','INCIDENT_LESSON','OWNERSHIP','TECH_DEBT'
    ));
ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_severity
    CHECK (severity IN ('INFO','WARNING','CRITICAL'));
ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_enforcement
    CHECK (enforcement IN ('REFERENCE','ADVISORY','REQUIRED'));

CREATE INDEX idx_knowledge_cards_engineering_policy
    ON knowledge_cards(repo_id,knowledge_kind,enforcement,publication_status);

COMMENT ON COLUMN knowledge_cards.knowledge_kind IS '可参与开发检查的工程知识类型';
COMMENT ON COLUMN knowledge_cards.severity IS '知识不满足时的业务严重程度';
COMMENT ON COLUMN knowledge_cards.enforcement IS '参考、建议或必须执行';
COMMENT ON COLUMN knowledge_cards.owner_account_id IS '工程知识负责人';
COMMENT ON COLUMN knowledge_cards.scope_payload IS '仓库内适用路径、符号和模块';
COMMENT ON COLUMN knowledge_cards.obligations_payload IS '命中知识后要求的测试、审批和开发动作';
COMMENT ON COLUMN knowledge_cards.last_verified_snapshot_id IS '最近完成人工或代码证据验证的仓库快照';
COMMENT ON COLUMN knowledge_cards.verification_note IS '最近验证说明';

DROP TRIGGER IF EXISTS trg_knowledge_card_revision ON knowledge_cards;
DROP FUNCTION IF EXISTS capture_knowledge_card_revision();

CREATE OR REPLACE FUNCTION capture_knowledge_card_revision() RETURNS trigger AS $$
BEGIN
    INSERT INTO knowledge_card_revisions(
        card_id,revision,repo_id,title,card_type,content,tags,publication_status,
        knowledge_kind,severity,enforcement,owner_account_id,scope_payload,obligations_payload,
        last_verified_snapshot_id,verification_note,changed_by,changed_at
    ) VALUES(
        NEW.id,NEW.revision,NEW.repo_id,NEW.title,NEW.card_type,NEW.content,NEW.tags,
        NEW.publication_status,NEW.knowledge_kind,NEW.severity,NEW.enforcement,NEW.owner_account_id,
        NEW.scope_payload,NEW.obligations_payload,NEW.last_verified_snapshot_id,
        NEW.verification_note,NEW.updated_by,NEW.updated_at
    )
    ON CONFLICT(card_id,revision) DO UPDATE SET
        title=EXCLUDED.title,card_type=EXCLUDED.card_type,content=EXCLUDED.content,
        tags=EXCLUDED.tags,publication_status=EXCLUDED.publication_status,
        knowledge_kind=EXCLUDED.knowledge_kind,severity=EXCLUDED.severity,
        enforcement=EXCLUDED.enforcement,owner_account_id=EXCLUDED.owner_account_id,
        scope_payload=EXCLUDED.scope_payload,obligations_payload=EXCLUDED.obligations_payload,
        last_verified_snapshot_id=EXCLUDED.last_verified_snapshot_id,
        verification_note=EXCLUDED.verification_note,changed_by=EXCLUDED.changed_by,
        changed_at=EXCLUDED.changed_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_knowledge_card_revision
AFTER INSERT OR UPDATE OF title,card_type,content,tags,publication_status,knowledge_kind,
    severity,enforcement,owner_account_id,scope_payload,obligations_payload,
    last_verified_snapshot_id,verification_note,revision ON knowledge_cards
FOR EACH ROW EXECUTE FUNCTION capture_knowledge_card_revision();
