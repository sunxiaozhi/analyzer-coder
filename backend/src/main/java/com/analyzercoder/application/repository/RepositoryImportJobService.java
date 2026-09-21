package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryImportJobMapper;
import com.analyzercoder.infrastructure.repository.RemoteRepositoryTargetPolicy;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthenticatedAccount;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 管理远程仓库导入任务的提交、查询和后台执行。
 *
 * <p>任务先持久化，再由后台 Worker 逐个认领。读取任务时必须校验所属账号，避免普通账号通过任务 ID 获取其他账号的仓库地址或执行结果。
 */
@Service
public class RepositoryImportJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryImportJobService.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final RepositoryImportJobMapper mapper;
    private final RepositoryCredentialService credentials;
    private final RepositorySourceImportService imports;
    private final RepositoryProjectDraftService drafts;
    private final RepositoryImportJobStateService jobState;
    private final RemoteRepositoryTargetPolicy remoteTargets;

    public RepositoryImportJobService(
            RepositoryImportJobMapper mapper,
            RepositoryCredentialService credentials,
            RepositorySourceImportService imports,
            RepositoryProjectDraftService drafts,
            RepositoryImportJobStateService jobState,
            RemoteRepositoryTargetPolicy remoteTargets) {
        this.mapper = mapper;
        this.credentials = credentials;
        this.imports = imports;
        this.drafts = drafts;
        this.jobState = jobState;
        this.remoteTargets = remoteTargets;
    }

    public JobView submit(
            AuthenticatedAccount actor,
            String name,
            String url,
            String branch,
            RepositorySourceType type,
            UUID credentialId) {
        return submit(actor, name, url, branch, type, credentialId, null);
    }

    public JobView submit(
            AuthenticatedAccount actor,
            String name,
            String url,
            String branch,
            RepositorySourceType type,
            UUID credentialId,
            UUID projectDraftId) {
        remoteTargets.requireAllowed(url);
        if (credentialId != null) {
            credentials.resolve(actor, credentialId, url);
        }
        if (type != RepositorySourceType.REMOTE_GIT && type != RepositorySourceType.GITLAB) {
            throw new IllegalArgumentException("仅支持远程 Git/GitLab 后台导入");
        }

        UUID id = UUID.randomUUID();
        if (projectDraftId != null) drafts.importing(actor, projectDraftId);
        mapper.insert(
                id,
                actor.id(),
                credentialId,
                type.name(),
                name.trim(),
                url,
                branch,
                projectDraftId);
        return view(mapper.find(id));
    }

    public JobView get(AuthenticatedAccount actor, UUID id) {
        Map<String, Object> row = mapper.find(id);
        requireVisible(actor, row);
        return view(row);
    }

    public List<JobView> list(AuthenticatedAccount actor) {
        return mapper.list(actor.id(), actor.isSuperAdmin()).stream().map(this::view).toList();
    }

    public JobView cancel(AuthenticatedAccount actor, UUID id) {
        if (mapper.requestCancel(id, actor.id(), actor.isSuperAdmin()) != 1) {
            throw new IllegalStateException("任务当前不能取消");
        }
        return get(actor, id);
    }

    /**
     * 认领并处理一个待执行任务。Git 网络与文件操作不得包在数据库事务中；任务状态由独立短事务持久化。
     *
     * @return 成功处理或取消任务时返回 {@code true}；无任务或处理失败时返回 {@code false}
     */
    public boolean processNext() {
        Map<String, Object> row = jobState.claim();
        if (row == null) {
            return false;
        }

        UUID id = uuid(row, "id");
        UUID projectDraftId = uuid(row, "project_draft_id");
        if (bool(row, "cancel_requested")) {
            jobState.cancel(id);
            failDraft(id, projectDraftId, "导入已取消，可重新配置代码来源后重试");
            return true;
        }

        try {
            jobState.step(id, "cloning");
            CodeRepository repository =
                    imports.importRemoteQueued(
                            string(row, "repository_name"),
                            string(row, "remote_url"),
                            string(row, "branch"),
                            RepositorySourceType.valueOf(string(row, "source_type")),
                            uuid(row, "credential_id"),
                            uuid(row, "account_id"));
            jobState.succeed(id, projectDraftId, repository.id().value());
            return true;
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            LOGGER.warn("远程仓库导入失败，任务已记录为 FAILED：jobId={}", id, exception);
            try {
                jobState.fail(id, message);
            } catch (RuntimeException persistenceFailure) {
                persistenceFailure.addSuppressed(exception);
                throw persistenceFailure;
            }
            failDraft(id, projectDraftId, message);
            return false;
        }
    }

    private void failDraft(UUID jobId, UUID projectDraftId, String message) {
        try {
            jobState.failDraft(projectDraftId, message);
        } catch (RuntimeException exception) {
            LOGGER.error("导入任务终态已保存，但项目草稿状态更新失败：jobId={}", jobId, exception);
        }
    }

    private void requireVisible(AuthenticatedAccount actor, Map<String, Object> row) {
        if (row == null) {
            throw new IllegalArgumentException("导入任务不存在");
        }
        if (!actor.isSuperAdmin() && !actor.id().equals(uuid(row, "account_id"))) {
            throw new ApiSecurityException(403, "FORBIDDEN", "无权查看该导入任务");
        }
    }

    private JobView view(Map<String, Object> row) {
        return new JobView(
                uuid(row, "id"),
                string(row, "source_type"),
                string(row, "repository_name"),
                string(row, "remote_url"),
                string(row, "branch"),
                string(row, "status"),
                string(row, "current_step"),
                string(row, "error_message"),
                uuid(row, "result_repository_id"),
                uuid(row, "project_draft_id"),
                instant(row, "created_at"),
                instant(row, "started_at"),
                instant(row, "finished_at"));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "导入失败";
        }
        return message.substring(0, Math.min(MAX_ERROR_MESSAGE_LENGTH, message.length()));
    }

    private static Object value(Map<String, Object> row, String key) {
        Object result = row.get(key);
        return result == null ? row.get(key.toUpperCase(Locale.ROOT)) : result;
    }

    private static String string(Map<String, Object> row, String key) {
        Object result = value(row, key);
        return result == null ? null : result.toString();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object result = value(row, key);
        if (result == null) {
            return null;
        }
        return result instanceof UUID id ? id : UUID.fromString(result.toString());
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object result = value(row, key);
        return result instanceof Boolean booleanValue && booleanValue;
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object result = value(row, key);
        if (result == null) {
            return null;
        }
        if (result instanceof Instant instant) {
            return instant;
        }
        if (result instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return Instant.parse(result.toString());
    }

    public record JobView(
            UUID id,
            String sourceType,
            String repositoryName,
            String remoteUrl,
            String branch,
            String status,
            String currentStep,
            String errorMessage,
            UUID resultRepositoryId,
            UUID projectDraftId,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {}
}
