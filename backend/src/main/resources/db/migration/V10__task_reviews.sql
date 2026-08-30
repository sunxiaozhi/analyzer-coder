CREATE TABLE task_reviews (
    id UUID PRIMARY KEY,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES accounts(id),
    client_request_id UUID NOT NULL,
    task TEXT,
    change_source VARCHAR(24) NOT NULL,
    base_ref VARCHAR(200),
    head_ref VARCHAR(200),
    model_config_id UUID REFERENCES llm_provider_configs(id) ON DELETE SET NULL,
    base_commit VARCHAR(64),
    head_commit VARCHAR(64),
    snapshot_id UUID NOT NULL,
    worktree_digest VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    result_payload JSONB,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT chk_task_reviews_source
        CHECK (change_source IN ('WORKTREE','SINGLE_COMMIT','COMMIT_RANGE')),
    CONSTRAINT chk_task_reviews_status
        CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_task_reviews_terminal
        CHECK (
            (status='RUNNING' AND finished_at IS NULL AND result_payload IS NULL)
            OR (status='COMPLETED' AND finished_at IS NOT NULL AND result_payload IS NOT NULL
                AND error_code IS NULL AND error_message IS NULL)
            OR (status='FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_task_reviews_client_request
    ON task_reviews(created_by,repo_id,client_request_id);
CREATE INDEX idx_task_reviews_repo_created
    ON task_reviews(repo_id,created_at DESC,id DESC);

COMMENT ON TABLE task_reviews IS '绑定 Git 版本、仓库快照和确定性知识事实的不可变任务审查';
COMMENT ON COLUMN task_reviews.client_request_id IS '调用方提供的幂等请求标识';
COMMENT ON COLUMN task_reviews.model_config_id IS '预留的模型配置，本阶段只保存且不调用模型';
COMMENT ON COLUMN task_reviews.result_payload IS '完成后不可变的完整审查结果 JSON';

CREATE OR REPLACE FUNCTION prevent_terminal_task_review_update() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('COMPLETED','FAILED') THEN
        RAISE EXCEPTION 'terminal task review is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_reviews_immutable
BEFORE UPDATE ON task_reviews
FOR EACH ROW EXECUTE FUNCTION prevent_terminal_task_review_update();
