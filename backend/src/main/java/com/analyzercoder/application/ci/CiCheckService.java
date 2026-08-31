package com.analyzercoder.application.ci;

import com.analyzercoder.application.evidence.Provenance;
import com.analyzercoder.application.evidence.TruthSource;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.knowledge.RepositoryGlobMatcher;
import com.analyzercoder.application.review.KnowledgeMatch;
import com.analyzercoder.application.review.KnowledgeMatchReason;
import com.analyzercoder.application.review.TaskReviewFinding;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.knowledge.KnowledgeEnforcement;
import com.analyzercoder.domain.knowledge.KnowledgeSeverity;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 把不可变 Task Review 与显式测试/审批回报转换为不会受模型影响的 CI 决策。 */
@Service
public class CiCheckService {
    public static final String POLICY_VERSION = "deterministic-ci-v1";

    private final TaskReviewService reviews;
    private final IntelligenceService intelligence;
    private final RepositoryGlobMatcher globs;

    public CiCheckService(
            TaskReviewService reviews,
            IntelligenceService intelligence,
            RepositoryGlobMatcher globs) {
        this.reviews = reviews;
        this.intelligence = intelligence;
        this.globs = globs;
    }

    public CiCheckResult check(
            CodeRepositoryId repositoryId, UUID reviewId, CiCheckRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        TaskReviewResult review = reviews.get(repositoryId, reviewId);
        requireCheckable(review, request.headCommit());

        ArrayList<CiFinding> blockers = new ArrayList<>();
        ArrayList<CiFinding> advisories = new ArrayList<>();
        Map<String, TestReport> testReports = testReports(request.tests());
        Map<UUID, ApprovalReport> approvalReports = approvalReports(request.approvals());

        prohibitedPaths(review, blockers);
        requiredTests(review, testReports, blockers, advisories);
        requiredApprovals(review, approvalReports, blockers, advisories);
        staleKnowledge(review, blockers, advisories);
        requiredKnowledgeUpdates(repositoryId, review, blockers, advisories);
        nonBlockingSignals(review, advisories);

        Decision decision = blockers.isEmpty() ? Decision.PASS : Decision.FAIL;
        return new CiCheckResult(
                POLICY_VERSION,
                decision,
                decision == Decision.PASS ? 0 : 1,
                repositoryId.value(),
                review.reviewId(),
                review.snapshotId(),
                request.headCommit(),
                List.copyOf(blockers),
                List.copyOf(advisories),
                Instant.now());
    }

    private static void requireCheckable(TaskReviewResult review, String requestedHead) {
        if (review.status() != TaskReviewResult.Status.COMPLETED || review.change() == null) {
            throw new CiCheckException("CI_REVIEW_NOT_COMPLETED", "CI 只能检查已完成且包含真实变化的审查");
        }
        String reviewedHead = review.change().headCommit();
        if (reviewedHead == null) {
            throw new CiCheckException(
                    "CI_COMMITTED_REVIEW_REQUIRED", "CI 不接受未提交工作区审查，请使用 Commit 或 PR/MR 审查");
        }
        if (requestedHead == null || !reviewedHead.equalsIgnoreCase(requestedHead)) {
            throw new CiCheckException(
                    "CI_HEAD_MISMATCH", "CI Head 与不可变审查的 Head Commit 不一致");
        }
    }

    private void prohibitedPaths(TaskReviewResult review, List<CiFinding> blockers) {
        Set<String> changedPaths = changedPaths(review);
        for (KnowledgeMatch knowledge : review.applicableKnowledge()) {
            if (knowledge.enforcement() != KnowledgeEnforcement.REQUIRED) {
                continue;
            }
            for (String pattern : knowledge.obligations().prohibitedPathPatterns()) {
                for (String path : changedPaths) {
                    if (globs.matches(pattern, path)) {
                        blockers.add(
                                finding(
                                        Severity.BLOCKING,
                                        "PROHIBITED_PATH_CHANGED",
                                        knowledge.knowledgeId() + ":" + pattern + ":" + path,
                                        "明确禁止的路径被修改",
                                        path + " 命中 REQUIRED 知识中的禁止规则 " + pattern,
                                        List.of(knowledge.knowledgeId()),
                                        List.of(path),
                                        knowledge.sources()));
                    }
                }
            }
        }
    }

    private static void requiredTests(
            TaskReviewResult review,
            Map<String, TestReport> reports,
            List<CiFinding> blockers,
            List<CiFinding> advisories) {
        for (TaskReviewFinding required : review.requiredTests()) {
            if (!hasDirectEvidence(required.evidence())) {
                advisories.add(graphOnly(required, "GRAPH_ONLY_TEST_REQUIREMENT"));
                continue;
            }
            TestReport report = reports.get(required.key());
            if (report == null) {
                blockers.add(
                        fromRequirement(
                                required,
                                "REQUIRED_TEST_NOT_REPORTED",
                                "必须测试没有执行回报",
                                "未报告测试结果: " + required.key()));
            } else if (report.status() != TestStatus.PASSED) {
                blockers.add(
                        fromRequirement(
                                required,
                                "REQUIRED_TEST_NOT_PASSED",
                                "必须测试未通过",
                                required.key() + " 状态为 " + report.status()));
            }
        }
    }

    private static void requiredApprovals(
            TaskReviewResult review,
            Map<UUID, ApprovalReport> reports,
            List<CiFinding> blockers,
            List<CiFinding> advisories) {
        for (TaskReviewFinding required : review.requiredApprovals()) {
            if (!hasDirectEvidence(required.evidence())) {
                advisories.add(graphOnly(required, "GRAPH_ONLY_APPROVAL_REQUIREMENT"));
                continue;
            }
            UUID accountId;
            try {
                accountId = UUID.fromString(required.key());
            } catch (IllegalArgumentException exception) {
                advisories.add(
                        fromRequirement(
                                required,
                                "APPROVER_ID_INVALID",
                                "审批要求无法自动检查",
                                "审批人标识不是平台账号 UUID"));
                continue;
            }
            ApprovalReport report = reports.get(accountId);
            if (report == null || report.status() != ApprovalStatus.APPROVED) {
                blockers.add(
                        fromRequirement(
                                required,
                                "REQUIRED_APPROVAL_MISSING",
                                "必要审批缺失",
                                report == null
                                        ? "没有该账号的审批回报"
                                        : "审批状态为 " + report.status()));
            }
        }
    }

    private static void staleKnowledge(
            TaskReviewResult review,
            List<CiFinding> blockers,
            List<CiFinding> advisories) {
        for (KnowledgeMatch knowledge : review.staleKnowledge()) {
            boolean criticalRequired =
                    knowledge.enforcement() == KnowledgeEnforcement.REQUIRED
                            && knowledge.severity() == KnowledgeSeverity.CRITICAL;
            if (!hasDirectEvidence(knowledge.reasons())) {
                advisories.add(
                        knowledgeFinding(
                                Severity.ADVISORY,
                                "GRAPH_ONLY_STALE_KNOWLEDGE",
                                "图谱单独推断的知识失效不阻断 CI",
                                knowledge));
            } else if (criticalRequired) {
                blockers.add(
                        knowledgeFinding(
                                Severity.BLOCKING,
                                "CRITICAL_REQUIRED_KNOWLEDGE_STALE",
                                "关键 REQUIRED 知识处于 " + knowledge.sourceVersionStatus(),
                                knowledge));
            } else {
                advisories.add(
                        knowledgeFinding(
                                Severity.ADVISORY,
                                "NONCRITICAL_STALE_KNOWLEDGE",
                                "非关键失效知识仅提示",
                                knowledge));
            }
        }
    }

    private void requiredKnowledgeUpdates(
            CodeRepositoryId repositoryId,
            TaskReviewResult review,
            List<CiFinding> blockers,
            List<CiFinding> advisories) {
        List<KnowledgeMatch> requiredUpdates =
                review.applicableKnowledge().stream()
                        .filter(
                                knowledge ->
                                        knowledge.enforcement() == KnowledgeEnforcement.REQUIRED
                                                && knowledge
                                                        .obligations()
                                                        .knowledgeUpdateRequired())
                        .toList();
        if (requiredUpdates.isEmpty()) {
            return;
        }
        LinkedHashSet<UUID> knowledgeRepositories = new LinkedHashSet<>();
        knowledgeRepositories.add(repositoryId.value());
        requiredUpdates.stream()
                .flatMap(knowledge -> knowledge.sources().stream())
                .filter(source -> source.sourceType() == TruthSource.VERIFIED_KNOWLEDGE)
                .map(Provenance::repositoryId)
                .filter(Objects::nonNull)
                .forEach(knowledgeRepositories::add);
        Map<UUID, IntelligenceService.KnowledgeCard> current =
                knowledgeRepositories.stream()
                        .flatMap(sourceRepositoryId -> intelligence.cards(sourceRepositoryId, true).stream())
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        IntelligenceService.KnowledgeCard::id,
                                        card -> card,
                                        (left, right) -> left,
                                        LinkedHashMap::new));
        for (KnowledgeMatch knowledge : requiredUpdates) {
            if (!hasDirectEvidence(knowledge.reasons())) {
                advisories.add(
                        knowledgeFinding(
                                Severity.ADVISORY,
                                "GRAPH_ONLY_KNOWLEDGE_UPDATE",
                                "图谱单独推断的知识同步要求不阻断 CI",
                                knowledge));
                continue;
            }
            IntelligenceService.KnowledgeCard latest = current.get(knowledge.knowledgeId());
            boolean updated =
                    latest != null
                            && latest.revision() > knowledge.revision()
                            && "PUBLISHED".equals(latest.publicationStatus())
                            && "APPROVED".equals(latest.reviewStatus())
                            && "CURRENT".equals(latest.sourceVersionStatus());
            if (!updated) {
                blockers.add(
                        knowledgeFinding(
                                Severity.BLOCKING,
                                "REQUIRED_KNOWLEDGE_UPDATE_MISSING",
                                "明确要求同步的知识尚未发布有效新修订",
                                knowledge));
            }
        }
    }

    private static void nonBlockingSignals(
            TaskReviewResult review, List<CiFinding> advisories) {
        if (review.change().partial()) {
            advisories.add(
                    finding(
                            Severity.ADVISORY,
                            "PARTIAL_CHANGE_DOES_NOT_FAIL_CI",
                            "partial-change",
                            "变更数据不完整",
                            "部分数据只提示；只有列出的确定性规则可以使 CI 失败",
                            List.of(),
                            List.of(),
                            List.of()));
        }
        if (!review.unknowns().isEmpty()) {
            advisories.add(
                    finding(
                            Severity.ADVISORY,
                            "UNKNOWNS_DO_NOT_FAIL_CI",
                            "unknowns",
                            "审查存在未知项",
                            review.unknowns().size() + " 个未知项未被扩大为失败结论",
                            List.of(),
                            List.of(),
                            review.unknowns().stream()
                                    .flatMap(item -> item.sources().stream())
                                    .distinct()
                                    .toList()));
        }
        if (!review.referenceCandidates().isEmpty()) {
            advisories.add(
                    finding(
                            Severity.ADVISORY,
                            "RETRIEVAL_CANDIDATES_IGNORED",
                            "retrieval",
                            "检索候选不参与 CI",
                            review.referenceCandidates().size() + " 个关键词/向量候选已排除",
                            List.of(),
                            List.of(),
                            List.of()));
        }
        if (review.modelSummary() != null
                || review.modelSummaryState().status()
                        != TaskReviewResult.ModelSummaryStatus.NOT_REQUESTED) {
            advisories.add(
                    finding(
                            Severity.ADVISORY,
                            "MODEL_SUGGESTIONS_IGNORED",
                            "model",
                            "模型建议不参与 CI",
                            "模型总结状态为 " + review.modelSummaryState().status(),
                            List.of(),
                            List.of(),
                            List.of()));
        }
    }

    private static Map<String, TestReport> testReports(List<TestReport> reports) {
        LinkedHashMap<String, TestReport> indexed = new LinkedHashMap<>();
        for (TestReport report : reports) {
            indexed.merge(
                    report.key(),
                    report,
                    (left, right) ->
                            left.status() == TestStatus.PASSED
                                            && right.status() == TestStatus.PASSED
                                    ? left
                                    : left.status() == TestStatus.FAILED
                                                    || right.status() == TestStatus.FAILED
                                            ? new TestReport(
                                                    left.key(), TestStatus.FAILED, right.evidenceUrl())
                                            : new TestReport(
                                                    left.key(), TestStatus.SKIPPED, right.evidenceUrl()));
        }
        return indexed;
    }

    private static Map<UUID, ApprovalReport> approvalReports(List<ApprovalReport> reports) {
        LinkedHashMap<UUID, ApprovalReport> indexed = new LinkedHashMap<>();
        for (ApprovalReport report : reports) {
            indexed.merge(
                    report.accountId(),
                    report,
                    (left, right) ->
                            left.status() == ApprovalStatus.APPROVED
                                            && right.status() == ApprovalStatus.APPROVED
                                    ? left
                                    : new ApprovalReport(
                                            left.accountId(),
                                            ApprovalStatus.REJECTED,
                                            right.evidenceUrl()));
        }
        return indexed;
    }

    private static Set<String> changedPaths(TaskReviewResult review) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        review.change()
                .changes()
                .forEach(
                        change -> {
                            if (change.oldPath() != null) {
                                paths.add(change.oldPath());
                            }
                            if (change.newPath() != null) {
                                paths.add(change.newPath());
                            }
                        });
        return paths;
    }

    private static boolean hasDirectEvidence(List<KnowledgeMatchReason> reasons) {
        return reasons.stream()
                .anyMatch(
                        reason ->
                                reason != null
                                        && reason.evidence() != null
                                        && reason.evidence().sourceType() != null
                                        && reason.evidence().sourceType()
                                        != KnowledgeMatchReason.EvidenceSource.GRAPH_INFERENCE);
    }

    private static CiFinding graphOnly(TaskReviewFinding finding, String code) {
        return fromRequirement(
                finding,
                code,
                "仅由图谱推断的要求不阻断 CI",
                "没有 Git 或代码直接证据");
    }

    private static CiFinding fromRequirement(
            TaskReviewFinding requirement, String code, String title, String detail) {
        return finding(
                code.startsWith("GRAPH_") || code.equals("APPROVER_ID_INVALID")
                        ? Severity.ADVISORY
                        : Severity.BLOCKING,
                code,
                requirement.key(),
                title,
                detail,
                requirement.knowledgeIds(),
                requirement.evidence().stream()
                        .map(reason -> reason.evidence().filePath())
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList(),
                requirement.sources());
    }

    private static CiFinding knowledgeFinding(
            Severity severity, String code, String detail, KnowledgeMatch knowledge) {
        return finding(
                severity,
                code,
                knowledge.knowledgeId().toString(),
                knowledge.title(),
                detail,
                List.of(knowledge.knowledgeId()),
                knowledge.reasons().stream()
                        .map(reason -> reason.evidence().filePath())
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList(),
                knowledge.sources());
    }

    private static CiFinding finding(
            Severity severity,
            String code,
            String key,
            String title,
            String detail,
            List<UUID> knowledgeIds,
            List<String> filePaths,
            List<Provenance> sources) {
        return new CiFinding(
                severity,
                code,
                key,
                title,
                detail,
                knowledgeIds,
                filePaths,
                sources);
    }

    public record CiCheckRequest(
            String headCommit, List<TestReport> tests, List<ApprovalReport> approvals) {
        public CiCheckRequest {
            headCommit = headCommit == null ? null : headCommit.trim().toLowerCase(Locale.ROOT);
            if (headCommit == null || !headCommit.matches("[0-9a-f]{40,64}")) {
                throw new IllegalArgumentException("headCommit 必须是完整 Git 对象 ID");
            }
            tests = immutable(tests, 200, "测试回报");
            approvals = immutable(approvals, 100, "审批回报");
        }
    }

    public record TestReport(String key, TestStatus status, String evidenceUrl) {
        public TestReport {
            key = required(key, 500, "测试标识");
            status = Objects.requireNonNull(status, "测试状态不能为空");
            evidenceUrl = optional(evidenceUrl, 1_000, "测试证据地址");
        }
    }

    public record ApprovalReport(UUID accountId, ApprovalStatus status, String evidenceUrl) {
        public ApprovalReport {
            accountId = Objects.requireNonNull(accountId, "审批账号不能为空");
            status = Objects.requireNonNull(status, "审批状态不能为空");
            evidenceUrl = optional(evidenceUrl, 1_000, "审批证据地址");
        }
    }

    public enum TestStatus {
        PASSED,
        FAILED,
        SKIPPED
    }

    public enum ApprovalStatus {
        APPROVED,
        REJECTED
    }

    public enum Decision {
        PASS,
        FAIL
    }

    public enum Severity {
        BLOCKING,
        ADVISORY
    }

    public record CiCheckResult(
            String policyVersion,
            Decision decision,
            int exitCode,
            UUID repositoryId,
            UUID reviewId,
            UUID snapshotId,
            String headCommit,
            List<CiFinding> blockingFindings,
            List<CiFinding> advisories,
            Instant evaluatedAt) {}

    public record CiFinding(
            Severity severity,
            String code,
            String key,
            String title,
            String detail,
            List<UUID> knowledgeIds,
            List<String> filePaths,
            List<Provenance> sources) {
        public CiFinding {
            knowledgeIds = List.copyOf(knowledgeIds);
            filePaths = List.copyOf(filePaths);
            sources = List.copyOf(sources);
        }
    }

    private static <T> List<T> immutable(List<T> values, int maximum, String label) {
        List<T> result = values == null ? List.of() : List.copyOf(values);
        if (result.size() > maximum || result.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + "无效或超过 " + maximum + " 项");
        }
        return result;
    }

    private static String required(String value, int maximum, String label) {
        String result = optional(value, maximum, label);
        if (result == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return result;
    }

    private static String optional(String value, int maximum, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (result.length() > maximum
                || result.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IllegalArgumentException(label + "格式无效或超过 " + maximum + " 个字符");
        }
        return result;
    }
}
