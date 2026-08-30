package com.analyzercoder.domain.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.UUID;

/** 保存知识命中后需要执行的测试、审批人与补充开发要求。 */
public record KnowledgeObligations(
        List<String> requiredTests,
        List<UUID> requiredApproverAccountIds,
        List<String> instructions,
        List<String> prohibitedPathPatterns,
        boolean knowledgeUpdateRequired) {
    public KnowledgeObligations {
        requiredTests = immutable(requiredTests);
        requiredApproverAccountIds = immutable(requiredApproverAccountIds);
        instructions = immutable(instructions);
        prohibitedPathPatterns = immutable(prohibitedPathPatterns);
    }

    /** 兼容 V9 三字段调用和历史 JSON；新 CI 规则必须通过五字段构造显式保存。 */
    public KnowledgeObligations(
            List<String> requiredTests,
            List<UUID> requiredApproverAccountIds,
            List<String> instructions) {
        this(requiredTests, requiredApproverAccountIds, instructions, List.of(), false);
    }

    public static KnowledgeObligations empty() {
        return new KnowledgeObligations(List.of(), List.of(), List.of(), List.of(), false);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return requiredTests.isEmpty()
                && requiredApproverAccountIds.isEmpty()
                && instructions.isEmpty()
                && prohibitedPathPatterns.isEmpty()
                && !knowledgeUpdateRequired;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
