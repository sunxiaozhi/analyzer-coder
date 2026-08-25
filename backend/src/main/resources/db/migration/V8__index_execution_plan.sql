ALTER TABLE index_jobs ADD COLUMN execution_mode VARCHAR(20);
ALTER TABLE index_jobs ADD COLUMN fallback_reason VARCHAR(64);

ALTER TABLE index_jobs ADD CONSTRAINT chk_index_jobs_execution_mode
    CHECK (execution_mode IS NULL OR execution_mode IN ('FULL','INCREMENTAL'));

COMMENT ON COLUMN index_jobs.execution_mode IS '实际执行模式；可能与请求的 job_type 不同';
COMMENT ON COLUMN index_jobs.fallback_reason IS '增量请求回退全量的稳定原因代码';
