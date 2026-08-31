ALTER TABLE task_reviews
    ADD CONSTRAINT uq_task_reviews_id_repo UNIQUE (id, repo_id);

CREATE TABLE task_review_outcomes (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    review_id UUID NOT NULL,
    reported_by UUID NOT NULL REFERENCES accounts(id),
    client_request_id UUID NOT NULL,
    final_commit VARCHAR(64) NOT NULL,
    commit_binding VARCHAR(40) NOT NULL,
    summary VARCHAR(4000) NOT NULL,
    tests_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    approvals_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    payload_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_review_outcomes_review
        FOREIGN KEY (review_id, repo_id) REFERENCES task_reviews(id, repo_id) ON DELETE CASCADE,
    CONSTRAINT chk_task_review_outcomes_commit
        CHECK (final_commit ~ '^[0-9a-f]{40,64}$'),
    CONSTRAINT chk_task_review_outcomes_binding
        CHECK (commit_binding IN ('EXACT_REVIEW_HEAD','REPORTER_ASSERTED_FINAL')),
    CONSTRAINT chk_task_review_outcomes_summary
        CHECK (length(trim(summary)) BETWEEN 1 AND 4000),
    CONSTRAINT chk_task_review_outcomes_tests
        CHECK (jsonb_typeof(tests_payload)='array'),
    CONSTRAINT chk_task_review_outcomes_approvals
        CHECK (jsonb_typeof(approvals_payload)='array'),
    CONSTRAINT chk_task_review_outcomes_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_task_review_outcomes_client_request
    ON task_review_outcomes(review_id, reported_by, client_request_id);
CREATE INDEX idx_task_review_outcomes_review_created
    ON task_review_outcomes(review_id, created_at DESC, id DESC);
CREATE INDEX idx_task_review_outcomes_repo_created
    ON task_review_outcomes(repo_id, created_at DESC, id DESC);

CREATE TABLE task_review_feedback (
    id UUID PRIMARY KEY,
    outcome_id UUID NOT NULL REFERENCES task_review_outcomes(id) ON DELETE CASCADE,
    kind VARCHAR(30) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_key VARCHAR(500) NOT NULL,
    knowledge_id UUID,
    knowledge_update_assessment VARCHAR(20),
    comment VARCHAR(2000) NOT NULL,
    evidence_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_task_review_feedback_kind
        CHECK (kind IN ('FALSE_POSITIVE','FALSE_NEGATIVE','KNOWLEDGE_UPDATE')),
    CONSTRAINT chk_task_review_feedback_target
        CHECK (target_type IN (
            'KNOWLEDGE','REQUIRED_TEST','REQUIRED_APPROVAL','STALE_KNOWLEDGE',
            'UNKNOWN','FILE','SYMBOL','OTHER'
        )),
    CONSTRAINT chk_task_review_feedback_key
        CHECK (length(trim(target_key)) BETWEEN 1 AND 500),
    CONSTRAINT chk_task_review_feedback_comment
        CHECK (length(trim(comment)) BETWEEN 1 AND 2000),
    CONSTRAINT chk_task_review_feedback_evidence
        CHECK (jsonb_typeof(evidence_urls)='array'),
    CONSTRAINT chk_task_review_feedback_knowledge_update
        CHECK (
            (kind='KNOWLEDGE_UPDATE'
                AND target_type='KNOWLEDGE'
                AND knowledge_id IS NOT NULL
                AND knowledge_update_assessment IN ('NEEDED','NOT_NEEDED','UNKNOWN'))
            OR
            (kind IN ('FALSE_POSITIVE','FALSE_NEGATIVE')
                AND knowledge_update_assessment IS NULL)
        )
);

CREATE INDEX idx_task_review_feedback_outcome
    ON task_review_feedback(outcome_id, created_at, id);
CREATE INDEX idx_task_review_feedback_knowledge
    ON task_review_feedback(knowledge_id)
    WHERE knowledge_id IS NOT NULL;

COMMENT ON TABLE task_review_outcomes IS
    '对不可变 Task Review 的追加式开发结果回报；每条保留报告人和幂等请求';
COMMENT ON COLUMN task_review_outcomes.commit_binding IS
    '仅完全等于审查 Head 时为精确绑定；其他 Commit 只是报告人声明';
COMMENT ON TABLE task_review_feedback IS
    '具名人工误报、漏报和知识更新判断；只供评测和改进，不触发知识修改';

CREATE OR REPLACE FUNCTION prevent_task_review_outcome_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'task review outcomes and feedback are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_review_outcomes_immutable
BEFORE UPDATE ON task_review_outcomes
FOR EACH ROW EXECUTE FUNCTION prevent_task_review_outcome_update();

CREATE TRIGGER trg_task_review_feedback_immutable
BEFORE UPDATE ON task_review_feedback
FOR EACH ROW EXECUTE FUNCTION prevent_task_review_outcome_update();
