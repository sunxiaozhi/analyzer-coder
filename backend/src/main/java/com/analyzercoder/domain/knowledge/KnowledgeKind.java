package com.analyzercoder.domain.knowledge;

/** 区分工程知识在开发流程中的语义，避免依赖自由文本分类做规则判断。 */
public enum KnowledgeKind {
    REFERENCE,
    BUSINESS_RULE,
    ARCH_DECISION,
    API_CONTRACT,
    DATA_CONSTRAINT,
    TEST_OBLIGATION,
    SECURITY_POLICY,
    RUNBOOK,
    INCIDENT_LESSON,
    OWNERSHIP,
    TECH_DEBT
}
