package com.analyzercoder.application.review;

import com.analyzercoder.application.change.GitChangeRequest;
import java.nio.file.Path;
import java.util.UUID;

/** 创建任务审查所需的幂等请求及 Git 版本选择。 */
public record TaskReviewRequest(
        UUID clientRequestId,
        String task,
        GitChangeRequest.Source changeSource,
        String baseRef,
        String headRef,
        UUID modelConfigId) {
    private static final int MAX_TASK_LENGTH = 2_000;

    public TaskReviewRequest {
        if (clientRequestId == null) {
            throw new IllegalArgumentException("clientRequestId 不能为空");
        }
        if (changeSource == null) {
            throw new IllegalArgumentException("changeSource 不能为空");
        }
        task = normalize(task, MAX_TASK_LENGTH, "任务描述");
        baseRef = normalize(baseRef, 200, "baseRef");
        headRef = normalize(headRef, 200, "headRef");
        switch (changeSource) {
            case WORKTREE -> {
                // baseRef/headRef 仅作为调用记录保存，工作区事实始终由 Git HEAD 解析。
            }
            case SINGLE_COMMIT -> {
                if (first(headRef, baseRef) == null) {
                    throw new IllegalArgumentException("单提交审查需要 headRef 或 baseRef");
                }
            }
            case COMMIT_RANGE -> {
                if (baseRef == null || headRef == null) {
                    throw new IllegalArgumentException("提交范围审查需要 baseRef 和 headRef");
                }
            }
        }
    }

    public GitChangeRequest gitRequest(Path repositoryRoot) {
        return switch (changeSource) {
            case WORKTREE -> GitChangeRequest.worktree(repositoryRoot);
            case SINGLE_COMMIT ->
                    GitChangeRequest.singleCommit(repositoryRoot, first(headRef, baseRef));
            case COMMIT_RANGE -> GitChangeRequest.commitRange(repositoryRoot, baseRef, headRef);
        };
    }

    private static String normalize(String value, int maximum, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " 不能超过 " + maximum + " 个字符");
        }
        return normalized;
    }

    private static String first(String primary, String fallback) {
        return primary == null ? fallback : primary;
    }
}
