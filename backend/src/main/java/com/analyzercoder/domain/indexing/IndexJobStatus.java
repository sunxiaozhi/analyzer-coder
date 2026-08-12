package com.analyzercoder.domain.indexing;

/** 定义索引任务在领域内允许使用的有限取值。 */
public enum IndexJobStatus {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    CANCELED
}
