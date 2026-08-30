package com.analyzercoder.domain.indexing;

/** 定义索引任务在领域内允许使用的有限取值。 */
public enum IndexJobType {
    FULL,
    INCREMENTAL,
    CODEGRAPH,
    KNOWLEDGE_DRIFT
}
