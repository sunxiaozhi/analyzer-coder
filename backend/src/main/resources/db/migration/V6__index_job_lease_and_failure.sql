ALTER TABLE index_jobs ADD COLUMN heartbeat_at TIMESTAMPTZ;
ALTER TABLE index_jobs ADD COLUMN timeout_at TIMESTAMPTZ;
ALTER TABLE index_jobs ADD COLUMN failure_code VARCHAR(64);

UPDATE index_jobs
SET heartbeat_at=COALESCE(started_at,created_at)
WHERE status IN ('RUNNING','CANCEL_REQUESTED');

COMMENT ON COLUMN index_jobs.heartbeat_at IS 'Worker 最近一次存活心跳；只表示进程仍在处理';
COMMENT ON COLUMN index_jobs.timeout_at IS '任务固定超时截止时间，心跳不会无限延长该截止时间';
COMMENT ON COLUMN index_jobs.failure_code IS '稳定失败代码，例如 CODEGRAPH_TIMEOUT、CODEGRAPH_BUILD_FAILED';

CREATE INDEX idx_index_jobs_running_timeout
    ON index_jobs(timeout_at)
    WHERE status IN ('RUNNING','CANCEL_REQUESTED');
