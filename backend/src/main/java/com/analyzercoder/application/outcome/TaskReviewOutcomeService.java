package com.analyzercoder.application.outcome;

import com.analyzercoder.application.review.TaskReviewFinding;
import com.analyzercoder.application.review.TaskReviewResult;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.TaskReviewOutcomeMapper;
import com.analyzercoder.infrastructure.persistence.model.TaskReviewFeedbackRow;
import com.analyzercoder.infrastructure.persistence.model.TaskReviewOutcomeRow;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 追加式保存真实开发结果和具名反馈，不回写或自动修改正式知识。 */
@Service
public class TaskReviewOutcomeService {
    private static final int MAX_TESTS = 200;
    private static final int MAX_APPROVALS = 100;
    private static final int MAX_FEEDBACK = 200;
    private static final int MAX_EVIDENCE_URLS = 20;
    private static final int MAX_LIST_LIMIT = 100;
    private static final TypeReference<List<TestResult>> TESTS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ApprovalResult>> APPROVALS_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<String>> URLS_TYPE = new TypeReference<>() {};

    private final TaskReviewService reviews;
    private final TaskReviewOutcomeMapper mapper;
    private final AuthService auth;
    private final ObjectMapper json;

    public TaskReviewOutcomeService(
            TaskReviewService reviews,
            TaskReviewOutcomeMapper mapper,
            AuthService auth,
            ObjectMapper json) {
        this.reviews = reviews;
        this.mapper = mapper;
        this.auth = auth;
        this.json = json;
    }

    @Transactional
    public OutcomeView report(
            CodeRepositoryId repositoryId,
            UUID reviewId,
            AuthenticatedAccount actor,
            OutcomeRequest requested,
            String sourceIp) {
        Objects.requireNonNull(actor, "报告账号不能为空");
        TaskReviewResult review = completedReview(repositoryId, reviewId);
        OutcomeRequest input = normalize(review, requested);
        String payloadHash = hash(write(input));
        Instant now = Instant.now();
        CommitBinding binding =
                review.change() != null
                                && review.change().headCommit() != null
                                && review.change().headCommit().equalsIgnoreCase(input.finalCommit())
                        ? CommitBinding.EXACT_REVIEW_HEAD
                        : CommitBinding.REPORTER_ASSERTED_FINAL;
        TaskReviewOutcomeRow row =
                new TaskReviewOutcomeRow(
                        UUID.randomUUID(),
                        repositoryId.value(),
                        reviewId,
                        actor.id(),
                        actor.displayName(),
                        input.clientRequestId(),
                        input.finalCommit(),
                        binding.name(),
                        input.summary(),
                        write(input.tests()),
                        write(input.approvals()),
                        payloadHash,
                        now);
        if (mapper.insertOutcome(row) == 0) {
            TaskReviewOutcomeRow existing =
                    mapper.findByClientRequest(reviewId, actor.id(), input.clientRequestId());
            if (existing == null) {
                throw new TaskReviewOutcomeException(
                        "TASK_OUTCOME_IDEMPOTENCY_LOOKUP_FAILED", "无法读取幂等结果回报");
            }
            if (!payloadHash.equals(existing.payloadHash())) {
                throw new TaskReviewOutcomeException(
                        "TASK_OUTCOME_IDEMPOTENCY_CONFLICT", "clientRequestId 已用于不同的结果回报");
            }
            return view(existing, review);
        }
        for (FeedbackInput item : input.feedback()) {
            mapper.insertFeedback(feedbackRow(row.id(), item, now));
        }
        auth.audit(
                actor.id(),
                null,
                repositoryId.value(),
                "TASK_REVIEW_OUTCOME_REPORTED",
                "SUCCESS",
                sourceIp);
        TaskReviewOutcomeRow persisted =
                mapper.findById(repositoryId.value(), reviewId, row.id());
        return view(persisted == null ? row : persisted, review);
    }

    public List<OutcomeView> list(
            CodeRepositoryId repositoryId, UUID reviewId, int limit, int offset) {
        TaskReviewResult review = completedReview(repositoryId, reviewId);
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        int safeOffset = Math.max(0, offset);
        return mapper.findByReview(repositoryId.value(), reviewId, safeLimit, safeOffset).stream()
                .map(row -> view(row, review))
                .toList();
    }

    public OutcomeView get(CodeRepositoryId repositoryId, UUID reviewId, UUID outcomeId) {
        TaskReviewResult review = completedReview(repositoryId, reviewId);
        TaskReviewOutcomeRow row =
                mapper.findById(repositoryId.value(), reviewId, outcomeId);
        if (row == null) {
            throw new TaskReviewOutcomeException("TASK_OUTCOME_NOT_FOUND", "开发结果回报不存在");
        }
        return view(row, review);
    }

    private TaskReviewResult completedReview(CodeRepositoryId repositoryId, UUID reviewId) {
        if (reviewId == null) {
            throw new IllegalArgumentException("审查 ID 不能为空");
        }
        TaskReviewResult review = reviews.get(repositoryId, reviewId);
        if (review.status() != TaskReviewResult.Status.COMPLETED || review.change() == null) {
            throw new TaskReviewOutcomeException(
                    "TASK_OUTCOME_REVIEW_NOT_COMPLETED", "只能对已完成且包含真实变化的审查回报结果");
        }
        return review;
    }

    private OutcomeRequest normalize(TaskReviewResult review, OutcomeRequest requested) {
        Objects.requireNonNull(requested, "结果回报不能为空");
        UUID clientRequestId =
                Objects.requireNonNull(requested.clientRequestId(), "clientRequestId 不能为空");
        String finalCommit = required(requested.finalCommit(), 64, "最终 Commit").toLowerCase(Locale.ROOT);
        if (!finalCommit.matches("[0-9a-f]{40,64}")) {
            throw new IllegalArgumentException("最终 Commit 必须是完整 Git 对象 ID");
        }
        String summary = required(requested.summary(), 4_000, "结果摘要");
        List<TestResult> tests = normalizeTests(requested.tests());
        List<ApprovalResult> approvals = normalizeApprovals(requested.approvals());
        List<FeedbackInput> feedback = normalizeFeedback(review, requested.feedback());
        return new OutcomeRequest(clientRequestId, finalCommit, summary, tests, approvals, feedback);
    }

    private static List<TestResult> normalizeTests(List<TestResult> requested) {
        List<TestResult> values = immutable(requested, MAX_TESTS, "测试结果");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        ArrayList<TestResult> normalized = new ArrayList<>();
        for (TestResult item : values) {
            String key = required(item.key(), 500, "测试标识");
            if (!keys.add(key)) {
                throw new IllegalArgumentException("测试标识不能重复: " + key);
            }
            normalized.add(
                    new TestResult(
                            key,
                            Objects.requireNonNull(item.status(), "测试状态不能为空"),
                            optionalUrl(item.evidenceUrl(), "测试证据地址")));
        }
        return List.copyOf(normalized);
    }

    private static List<ApprovalResult> normalizeApprovals(List<ApprovalResult> requested) {
        List<ApprovalResult> values = immutable(requested, MAX_APPROVALS, "审批结果");
        LinkedHashSet<UUID> accounts = new LinkedHashSet<>();
        ArrayList<ApprovalResult> normalized = new ArrayList<>();
        for (ApprovalResult item : values) {
            UUID accountId = Objects.requireNonNull(item.accountId(), "审批账号不能为空");
            if (!accounts.add(accountId)) {
                throw new IllegalArgumentException("审批账号不能重复: " + accountId);
            }
            normalized.add(
                    new ApprovalResult(
                            accountId,
                            Objects.requireNonNull(item.status(), "审批状态不能为空"),
                            optionalUrl(item.evidenceUrl(), "审批证据地址")));
        }
        return List.copyOf(normalized);
    }

    private static List<FeedbackInput> normalizeFeedback(
            TaskReviewResult review, List<FeedbackInput> requested) {
        List<FeedbackInput> values = immutable(requested, MAX_FEEDBACK, "人工反馈");
        ArrayList<FeedbackInput> normalized = new ArrayList<>();
        for (FeedbackInput item : values) {
            FeedbackKind kind = Objects.requireNonNull(item.kind(), "反馈类型不能为空");
            FeedbackTargetType targetType =
                    Objects.requireNonNull(item.targetType(), "反馈对象类型不能为空");
            UUID knowledgeId = item.knowledgeId();
            KnowledgeUpdateAssessment assessment = item.knowledgeUpdateAssessment();
            String targetKey = required(item.targetKey(), 500, "反馈对象");
            if (kind == FeedbackKind.KNOWLEDGE_UPDATE) {
                if (targetType != FeedbackTargetType.KNOWLEDGE
                        || knowledgeId == null
                        || assessment == null
                        || !reviewKnowledgeIds(review).contains(knowledgeId)) {
                    throw new IllegalArgumentException("知识更新判断必须指向本次审查中的知识");
                }
                targetKey = knowledgeId.toString();
            } else {
                if (assessment != null) {
                    throw new IllegalArgumentException("误报/漏报反馈不能携带知识更新判断");
                }
                if (kind == FeedbackKind.FALSE_POSITIVE
                        && !existingReviewTarget(review, targetType, targetKey)) {
                    throw new IllegalArgumentException("误报反馈必须指向本次审查中真实存在的对象");
                }
            }
            List<String> evidenceUrls =
                    immutable(item.evidenceUrls(), MAX_EVIDENCE_URLS, "反馈证据地址").stream()
                            .map(value -> requiredUrl(value, "反馈证据地址"))
                            .distinct()
                            .toList();
            normalized.add(
                    new FeedbackInput(
                            kind,
                            targetType,
                            targetKey,
                            knowledgeId,
                            assessment,
                            required(item.comment(), 2_000, "反馈说明"),
                            evidenceUrls));
        }
        return List.copyOf(normalized);
    }

    private static Set<UUID> reviewKnowledgeIds(TaskReviewResult review) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        review.applicableKnowledge().forEach(item -> ids.add(item.knowledgeId()));
        review.staleKnowledge().forEach(item -> ids.add(item.knowledgeId()));
        return ids;
    }

    private static boolean existingReviewTarget(
            TaskReviewResult review, FeedbackTargetType type, String key) {
        return switch (type) {
            case KNOWLEDGE ->
                    review.applicableKnowledge().stream()
                            .anyMatch(item -> item.knowledgeId().toString().equals(key));
            case STALE_KNOWLEDGE ->
                    review.staleKnowledge().stream()
                            .anyMatch(item -> item.knowledgeId().toString().equals(key));
            case REQUIRED_TEST -> review.requiredTests().stream().anyMatch(item -> item.key().equals(key));
            case REQUIRED_APPROVAL ->
                    review.requiredApprovals().stream().anyMatch(item -> item.key().equals(key));
            case UNKNOWN -> review.unknowns().stream().anyMatch(item -> item.key().equals(key));
            case FILE ->
                    review.change().changes().stream()
                            .anyMatch(
                                    item ->
                                            key.equals(item.newPath()) || key.equals(item.oldPath()));
            case SYMBOL -> review.changedSymbols().stream().anyMatch(item -> item.name().equals(key));
            case OTHER -> false;
        };
    }

    private TaskReviewFeedbackRow feedbackRow(
            UUID outcomeId, FeedbackInput input, Instant now) {
        return new TaskReviewFeedbackRow(
                UUID.randomUUID(),
                outcomeId,
                input.kind().name(),
                input.targetType().name(),
                input.targetKey(),
                input.knowledgeId(),
                input.knowledgeUpdateAssessment() == null
                        ? null
                        : input.knowledgeUpdateAssessment().name(),
                input.comment(),
                write(input.evidenceUrls()),
                now);
    }

    private OutcomeView view(TaskReviewOutcomeRow row, TaskReviewResult review) {
        List<TestResult> tests = read(row.testsPayload(), TESTS_TYPE, "测试结果");
        List<ApprovalResult> approvals = read(row.approvalsPayload(), APPROVALS_TYPE, "审批结果");
        List<FeedbackView> feedback =
                mapper.feedback(row.id()).stream().map(this::feedbackView).toList();
        return new OutcomeView(
                row.id(),
                row.repositoryId(),
                row.reviewId(),
                row.reportedBy(),
                row.reporterDisplayName(),
                row.clientRequestId(),
                row.finalCommit(),
                CommitBinding.valueOf(row.commitBinding()),
                row.summary(),
                tests,
                approvals,
                feedback,
                coverage(review, tests, approvals),
                row.createdAt());
    }

    private FeedbackView feedbackView(TaskReviewFeedbackRow row) {
        return new FeedbackView(
                row.id(),
                FeedbackKind.valueOf(row.kind()),
                FeedbackTargetType.valueOf(row.targetType()),
                row.targetKey(),
                row.knowledgeId(),
                row.knowledgeUpdateAssessment() == null
                        ? null
                        : KnowledgeUpdateAssessment.valueOf(row.knowledgeUpdateAssessment()),
                row.comment(),
                read(row.evidenceUrlsPayload(), URLS_TYPE, "反馈证据地址"),
                row.createdAt());
    }

    private static OutcomeCoverage coverage(
            TaskReviewResult review, List<TestResult> tests, List<ApprovalResult> approvals) {
        List<String> requiredTests =
                review.requiredTests().stream().map(TaskReviewFinding::key).distinct().toList();
        Set<String> reportedTests =
                tests.stream().map(TestResult::key).collect(java.util.stream.Collectors.toSet());
        List<String> requiredApprovals =
                review.requiredApprovals().stream().map(TaskReviewFinding::key).distinct().toList();
        Set<String> reportedApprovals =
                approvals.stream()
                        .map(item -> item.accountId().toString())
                        .collect(java.util.stream.Collectors.toSet());
        return new OutcomeCoverage(
                requiredTests,
                requiredTests.stream().filter(reportedTests::contains).toList(),
                requiredTests.stream().filter(item -> !reportedTests.contains(item)).toList(),
                requiredApprovals,
                requiredApprovals.stream().filter(reportedApprovals::contains).toList(),
                requiredApprovals.stream()
                        .filter(item -> !reportedApprovals.contains(item))
                        .toList());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new TaskReviewOutcomeException(
                    "TASK_OUTCOME_SERIALIZATION_FAILED", "无法保存开发结果回报", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type, String label) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new TaskReviewOutcomeException(
                    "TASK_OUTCOME_PAYLOAD_INVALID", "无法恢复已保存的" + label, exception);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
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
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String result = value.trim();
        if (result.length() > maximum
                || result.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IllegalArgumentException(label + "格式无效或超过 " + maximum + " 个字符");
        }
        return result;
    }

    private static String optionalUrl(String value, String label) {
        return value == null || value.isBlank() ? null : requiredUrl(value, label);
    }

    private static String requiredUrl(String value, String label) {
        String result = required(value, 1_000, label);
        try {
            URI uri = new URI(result);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(label + "必须是 HTTP(S) 地址");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(label + "必须是有效地址");
        }
    }

    public record OutcomeRequest(
            UUID clientRequestId,
            String finalCommit,
            String summary,
            List<TestResult> tests,
            List<ApprovalResult> approvals,
            List<FeedbackInput> feedback) {}

    public record TestResult(String key, TestStatus status, String evidenceUrl) {}

    public record ApprovalResult(UUID accountId, ApprovalStatus status, String evidenceUrl) {}

    public record FeedbackInput(
            FeedbackKind kind,
            FeedbackTargetType targetType,
            String targetKey,
            UUID knowledgeId,
            KnowledgeUpdateAssessment knowledgeUpdateAssessment,
            String comment,
            List<String> evidenceUrls) {}

    public record OutcomeView(
            UUID id,
            UUID repositoryId,
            UUID reviewId,
            UUID reportedBy,
            String reporterDisplayName,
            UUID clientRequestId,
            String finalCommit,
            CommitBinding commitBinding,
            String summary,
            List<TestResult> tests,
            List<ApprovalResult> approvals,
            List<FeedbackView> feedback,
            OutcomeCoverage coverage,
            Instant createdAt) {}

    public record FeedbackView(
            UUID id,
            FeedbackKind kind,
            FeedbackTargetType targetType,
            String targetKey,
            UUID knowledgeId,
            KnowledgeUpdateAssessment knowledgeUpdateAssessment,
            String comment,
            List<String> evidenceUrls,
            Instant createdAt) {}

    public record OutcomeCoverage(
            List<String> requiredTests,
            List<String> reportedRequiredTests,
            List<String> missingRequiredTests,
            List<String> requiredApprovals,
            List<String> reportedRequiredApprovals,
            List<String> missingRequiredApprovals) {}

    public enum CommitBinding {
        EXACT_REVIEW_HEAD,
        REPORTER_ASSERTED_FINAL
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

    public enum FeedbackKind {
        FALSE_POSITIVE,
        FALSE_NEGATIVE,
        KNOWLEDGE_UPDATE
    }

    public enum FeedbackTargetType {
        KNOWLEDGE,
        REQUIRED_TEST,
        REQUIRED_APPROVAL,
        STALE_KNOWLEDGE,
        UNKNOWN,
        FILE,
        SYMBOL,
        OTHER
    }

    public enum KnowledgeUpdateAssessment {
        NEEDED,
        NOT_NEEDED,
        UNKNOWN
    }
}
