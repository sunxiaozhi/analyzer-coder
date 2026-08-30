DROP TRIGGER IF EXISTS trg_repository_knowledge_stale ON repositories;
DROP FUNCTION IF EXISTS mark_repository_knowledge_stale();

CREATE TABLE knowledge_drift_events (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    card_id UUID NOT NULL REFERENCES knowledge_cards(id) ON DELETE CASCADE,
    card_revision INTEGER NOT NULL CHECK (card_revision > 0),
    from_snapshot_id UUID,
    to_snapshot_id UUID NOT NULL,
    from_commit VARCHAR(128),
    to_commit VARCHAR(128),
    previous_status VARCHAR(32) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(40) NOT NULL,
    reasons_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    note TEXT,
    actor_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_knowledge_drift_previous_status
        CHECK (previous_status IN ('UNVERIFIED','CURRENT','SUSPECT','STALE')),
    CONSTRAINT chk_knowledge_drift_result_status
        CHECK (result_status IN ('CURRENT','SUSPECT','STALE')),
    CONSTRAINT chk_knowledge_drift_trigger_type
        CHECK (trigger_type IN ('AUTOMATIC_DIFF','MANUAL_CONFIRM_CURRENT','MANUAL_MARK_STALE')),
    CONSTRAINT chk_knowledge_drift_reasons_payload CHECK (jsonb_typeof(reasons_payload)='array')
);

CREATE UNIQUE INDEX uq_knowledge_drift_automatic_snapshot
    ON knowledge_drift_events(card_id,card_revision,to_snapshot_id,trigger_type)
    WHERE trigger_type='AUTOMATIC_DIFF';
CREATE INDEX idx_knowledge_drift_card_created
    ON knowledge_drift_events(repo_id,card_id,created_at DESC,id DESC);

COMMENT ON TABLE knowledge_drift_events IS '知识来源版本的自动漂移与人工复核审计';
COMMENT ON COLUMN knowledge_drift_events.reasons_payload IS '触发状态变化的结构化 Git、代码引用或符号证据';
