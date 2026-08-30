package com.analyzercoder.application.evidence;

/** 证据的真实性边界；枚举值描述来源性质，不表达概率或置信度。 */
public enum TruthSource {
    GIT_FACT,
    CODE_FACT,
    VERIFIED_KNOWLEDGE,
    GRAPH_INFERENCE,
    RETRIEVAL_CANDIDATE,
    MODEL_SUGGESTION,
    UNKNOWN
}
