package com.analyzercoder.application.knowledge;

import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeKind;
import com.analyzercoder.domain.knowledge.KnowledgeObligations;
import com.analyzercoder.domain.knowledge.KnowledgeScope;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/** 集中校验工程知识结构，保证 REST、持久化和后续任务审查使用同一套规则。 */
@Component
public class EngineeringKnowledgePolicy {
    private static final int MAX_PATH_PATTERNS = 50;
    private static final int MAX_SYMBOLS = 50;
    private static final int MAX_MODULES = 20;
    private static final int MAX_REQUIRED_TESTS = 50;
    private static final int MAX_APPROVERS = 20;
    private static final int MAX_INSTRUCTIONS = 50;
    private static final int MAX_PROHIBITED_PATH_PATTERNS = 50;

    public ValidatedKnowledge validate(
            String requestedKind,
            String requestedSeverity,
            String requestedEnforcement,
            UUID ownerAccountId,
            KnowledgeScope requestedScope,
            KnowledgeObligations requestedObligations) {
        KnowledgeKind kind =
                parse(requestedKind, KnowledgeKind.class, KnowledgeKind.REFERENCE, "工程知识类型无效");
        KnowledgeSeverity severity =
                parse(
                        requestedSeverity,
                        KnowledgeSeverity.class,
                        KnowledgeSeverity.INFO,
                        "知识严重程度无效");
        KnowledgeEnforcement enforcement =
                parse(
                        requestedEnforcement,
                        KnowledgeEnforcement.class,
                        KnowledgeEnforcement.REFERENCE,
                        "知识执行级别无效");
        KnowledgeScope scope = normalizeScope(requestedScope);
        KnowledgeObligations obligations = normalizeObligations(requestedObligations);
        if (enforcement == KnowledgeEnforcement.REFERENCE && !obligations.isEmpty()) {
            throw new IllegalArgumentException("参考知识不能设置强制测试、审批或开发要求");
        }
        return new ValidatedKnowledge(
                kind, severity, enforcement, ownerAccountId, scope, obligations);
    }

    public void validateForPublication(
            KnowledgeEnforcement enforcement,
            UUID ownerAccountId,
            KnowledgeScope scope,
            String reviewStatus,
            String sourceVersionStatus) {
        if (enforcement != KnowledgeEnforcement.REQUIRED) {
            return;
        }
        if (ownerAccountId == null) {
            throw new IllegalStateException("必须执行的工程知识需要指定负责人后才能发布");
        }
        if (scope == null || scope.isEmpty()) {
            throw new IllegalStateException("必须执行的工程知识需要设置适用范围后才能发布");
        }
        if (!"APPROVED".equals(reviewStatus)) {
            throw new IllegalStateException("必须执行的工程知识尚未通过人工评审，不能发布");
        }
        if (!"CURRENT".equals(sourceVersionStatus)) {
            throw new IllegalStateException("必须执行的工程知识需要在当前代码快照验证后才能发布");
        }
    }

    private static KnowledgeScope normalizeScope(KnowledgeScope scope) {
        KnowledgeScope value = scope == null ? KnowledgeScope.empty() : scope;
        List<String> paths =
                normalizeStrings(
                        value.pathPatterns(),
                        MAX_PATH_PATTERNS,
                        300,
                        "适用路径",
                        RepositoryGlobMatcher::normalizePattern);
        List<String> symbols =
                normalizeStrings(value.symbols(), MAX_SYMBOLS, 200, "适用符号", String::trim);
        List<String> modules =
                normalizeStrings(value.modules(), MAX_MODULES, 200, "适用模块", String::trim);
        return new KnowledgeScope(paths, symbols, modules);
    }

    private static KnowledgeObligations normalizeObligations(KnowledgeObligations obligations) {
        KnowledgeObligations value =
                obligations == null ? KnowledgeObligations.empty() : obligations;
        List<String> tests =
                normalizeStrings(
                        value.requiredTests(), MAX_REQUIRED_TESTS, 500, "必需测试", String::trim);
        List<UUID> approvers =
                value.requiredApproverAccountIds().stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .limit(MAX_APPROVERS + 1L)
                        .toList();
        if (approvers.size() > MAX_APPROVERS) {
            throw new IllegalArgumentException("审批人最多允许 " + MAX_APPROVERS + " 个");
        }
        List<String> instructions =
                normalizeStrings(
                        value.instructions(), MAX_INSTRUCTIONS, 2_000, "开发要求", String::trim);
        List<String> prohibitedPaths =
                normalizeStrings(
                        value.prohibitedPathPatterns(),
                        MAX_PROHIBITED_PATH_PATTERNS,
                        300,
                        "禁止修改路径",
                        RepositoryGlobMatcher::normalizePattern);
        return new KnowledgeObligations(
                tests,
                approvers,
                instructions,
                prohibitedPaths,
                value.knowledgeUpdateRequired());
    }

    private static List<String> normalizeStrings(
            List<String> values,
            int maximumCount,
            int maximumLength,
            String label,
            UnaryOperator<String> normalizer) {
        List<String> normalized =
                (values == null ? List.<String>of() : values)
                        .stream()
                                .filter(Objects::nonNull)
                                .map(normalizer)
                                .filter(value -> !value.isBlank())
                                .distinct()
                                .limit(maximumCount + 1L)
                                .toList();
        if (normalized.size() > maximumCount) {
            throw new IllegalArgumentException(label + "最多允许 " + maximumCount + " 项");
        }
        if (normalized.stream().anyMatch(value -> value.length() > maximumLength)) {
            throw new IllegalArgumentException(label + "单项长度不能超过 " + maximumLength + " 个字符");
        }
        return normalized;
    }

    private static <T extends Enum<T>> T parse(
            String input, Class<T> type, T fallback, String errorMessage) {
        String value = input == null || input.isBlank() ? fallback.name() : input.trim();
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    public record ValidatedKnowledge(
            KnowledgeKind kind,
            KnowledgeSeverity severity,
            KnowledgeEnforcement enforcement,
            UUID ownerAccountId,
            KnowledgeScope scope,
            KnowledgeObligations obligations) {}
}
