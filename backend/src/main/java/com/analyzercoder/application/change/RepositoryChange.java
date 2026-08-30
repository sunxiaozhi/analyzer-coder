package com.analyzercoder.application.change;

import java.util.List;

/** 一次版本化 Git 分析产生的结构化文件变化事实。 */
public record RepositoryChange(
        GitChangeRequest.Source source,
        String baseCommit,
        String headCommit,
        String worktreeDigest,
        boolean partial,
        List<FileChange> changes,
        List<Limitation> limitations) {
    public RepositoryChange {
        changes = changes == null ? List.of() : List.copyOf(changes);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public enum ChangeType {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED,
        COPIED
    }

    public record FileChange(
            ChangeType type,
            String oldPath,
            String newPath,
            boolean binary,
            Long additions,
            Long deletions,
            List<Hunk> hunks) {
        public FileChange {
            hunks = hunks == null ? List.of() : List.copyOf(hunks);
        }
    }

    public record Hunk(int oldStart, int oldCount, int newStart, int newCount) {}

    public record Limitation(String code, String detail) {}
}
