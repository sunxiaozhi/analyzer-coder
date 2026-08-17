-- Extend the one-row-per-answer model so each row represents one turn in a thread.
-- Published migrations remain immutable; existing rows are backfilled as single-turn threads.
ALTER TABLE qa_conversations
    ADD COLUMN thread_id UUID DEFAULT gen_random_uuid(),
    ADD COLUMN turn_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN stop_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN finished_at TIMESTAMPTZ;

UPDATE qa_conversations
SET thread_id = id,
    turn_no = 1,
    status = 'COMPLETED',
    stop_requested = FALSE,
    started_at = created_at,
    finished_at = updated_at;

ALTER TABLE qa_conversations
    ALTER COLUMN thread_id SET NOT NULL,
    ALTER COLUMN turn_no SET NOT NULL,
    ADD CONSTRAINT chk_qa_conversations_turn_no CHECK (turn_no > 0),
    ADD CONSTRAINT chk_qa_conversations_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'STOPPED', 'FAILED')
    );

CREATE UNIQUE INDEX uk_qa_conversations_thread_turn
    ON qa_conversations(thread_id, turn_no);

CREATE INDEX idx_qa_conversations_thread_created
    ON qa_conversations(thread_id, turn_no, created_at);

CREATE INDEX idx_qa_conversations_account_repo_thread
    ON qa_conversations(account_id, repo_id, thread_id, updated_at DESC);

COMMENT ON COLUMN qa_conversations.thread_id IS '多轮问答线程标识；首轮记录通常以自身 ID 作为线程标识';
COMMENT ON COLUMN qa_conversations.turn_no IS '当前记录在线程内的轮次，从 1 开始';
COMMENT ON COLUMN qa_conversations.status IS '生成状态：RUNNING、COMPLETED、STOPPED 或 FAILED';
COMMENT ON COLUMN qa_conversations.stop_requested IS '用户是否已请求停止当前轮次的生成';
COMMENT ON COLUMN qa_conversations.started_at IS '当前轮次开始生成的时间';
COMMENT ON COLUMN qa_conversations.finished_at IS '当前轮次完成、停止或失败的时间';
