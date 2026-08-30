package com.analyzercoder.application.pullrequest;

import com.analyzercoder.application.change.RepositoryChange;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/** GitHub PR 与 GitLab MR 的最小统一边界；访问令牌只在提供方调用期间可见。 */
public interface PullRequestProvider {
    ProviderKind kind();

    PullRequestSnapshot fetch(Reference reference, AccessToken accessToken);

    CommentResult upsertReviewComment(
            Reference reference, String marker, String body, AccessToken accessToken);

    enum ProviderKind {
        GITHUB,
        GITLAB
    }

    record Reference(URI apiBaseUrl, String projectPath, long number) {
        public Reference {
            if (apiBaseUrl == null || projectPath == null || projectPath.isBlank() || number < 1) {
                throw new IllegalArgumentException("PR/MR 引用不完整");
            }
        }

        public String externalId() {
            return projectPath + "#" + number;
        }
    }

    record PullRequestSnapshot(
            ProviderKind provider,
            String externalId,
            long number,
            String title,
            String webUrl,
            String author,
            boolean draft,
            String baseSha,
            String headSha,
            String patch,
            boolean partial,
            List<RepositoryChange.Limitation> limitations,
            Instant fetchedAt) {
        public PullRequestSnapshot {
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            if (provider == null
                    || externalId == null
                    || baseSha == null
                    || headSha == null
                    || patch == null
                    || fetchedAt == null) {
                throw new IllegalArgumentException("PR/MR 快照缺少必要事实");
            }
        }
    }

    record CommentResult(CommentAction action, String commentId, String commentUrl) {}

    enum CommentAction {
        CREATED,
        UPDATED
    }

    /** 不是 record，避免日志或调试输出自动包含访问令牌。 */
    final class AccessToken {
        private final String value;

        public AccessToken(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("PR/MR 集成需要已绑定的访问令牌");
            }
            this.value = value;
        }

        String value() {
            return value;
        }

        @Override
        public String toString() {
            return "AccessToken[REDACTED]";
        }
    }
}
