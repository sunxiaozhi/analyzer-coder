package com.analyzercoder.domain.indexing;

public enum IndexJobStatus {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    CANCELED
}
