package com.analyzercoder.application.change;

import java.nio.file.Path;
import java.util.Objects;

/** 描述一次只读 Git 变更分析的来源和版本边界。 */
public record GitChangeRequest(Path repositoryRoot, Source source, String baseRef, String headRef) {
    private static final int MAX_REF_LENGTH = 200;

    public GitChangeRequest {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        Objects.requireNonNull(source, "source must not be null");
        repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        baseRef = normalizeRef(baseRef);
        headRef = normalizeRef(headRef);
        switch (source) {
            case WORKTREE -> requireRefs(baseRef == null && headRef == null, "工作区分析不能指定提交参数");
            case SINGLE_COMMIT ->
                    requireRefs(baseRef == null && headRef != null, "单提交分析必须且只能指定目标提交");
            case COMMIT_RANGE -> requireRefs(baseRef != null && headRef != null, "提交范围分析必须指定起止提交");
        }
    }

    public static GitChangeRequest worktree(Path repositoryRoot) {
        return new GitChangeRequest(repositoryRoot, Source.WORKTREE, null, null);
    }

    public static GitChangeRequest singleCommit(Path repositoryRoot, String commitRef) {
        return new GitChangeRequest(repositoryRoot, Source.SINGLE_COMMIT, null, commitRef);
    }

    public static GitChangeRequest commitRange(
            Path repositoryRoot, String baseRef, String headRef) {
        return new GitChangeRequest(repositoryRoot, Source.COMMIT_RANGE, baseRef, headRef);
    }

    private static String normalizeRef(String requestedRef) {
        if (requestedRef == null) {
            return null;
        }
        String ref = requestedRef.trim();
        if (ref.isEmpty()) {
            return null;
        }
        if (ref.length() > MAX_REF_LENGTH) {
            throw new IllegalArgumentException("Git Ref 长度不能超过 " + MAX_REF_LENGTH + " 个字符");
        }
        if (ref.startsWith("-") || ref.chars().anyMatch(GitChangeRequest::isControlCharacter)) {
            throw new IllegalArgumentException("Git Ref 包含不安全参数");
        }
        return ref;
    }

    private static boolean isControlCharacter(int value) {
        return value < 32 || value == 127;
    }

    private static void requireRefs(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    public enum Source {
        WORKTREE,
        SINGLE_COMMIT,
        COMMIT_RANGE
    }
}
