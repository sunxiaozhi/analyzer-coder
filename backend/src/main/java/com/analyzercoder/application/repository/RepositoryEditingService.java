package com.analyzercoder.application.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryMapper;
import com.analyzercoder.infrastructure.persistence.model.RepositoryRow;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.ApiSecurityException;
import com.analyzercoder.security.AuthService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排仓库编辑相关应用流程，协调领域对象、权限校验与基础设施端口。 */
@Service
public class RepositoryEditingService {
    private final RepositoryMapper mapper;
    private final AccessControlService accessControl;
    private final AuthService authService;

    public RepositoryEditingService(
            RepositoryMapper mapper, AccessControlService accessControl, AuthService authService) {
        this.mapper = mapper;
        this.accessControl = accessControl;
        this.authService = authService;
    }

    public Metadata metadata(UUID repositoryId) {
        RepositoryRow row = requireRow(repositoryId);
        return new Metadata(row.description(), row.repositoryVersion());
    }

    @Transactional
    public void update(
            AuthenticatedAccount actor,
            UUID repositoryId,
            String name,
            String description,
            String defaultBranch,
            long expectedVersion,
            String sourceIp) {
        accessControl.require(
                actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.MANAGE);
        RepositoryRow current = requireRow(repositoryId);
        String nextName = normalizeName(name);
        String nextDescription = description == null ? "" : description.trim();
        if (nextDescription.length() > 500) {
            throw new IllegalArgumentException("仓库描述不能超过 500 个字符");
        }
        String nextBranch = defaultBranch == null ? current.defaultBranch() : defaultBranch.trim();
        if (nextBranch != null && nextBranch.length() > 255) {
            throw new IllegalArgumentException("默认分支不能超过 255 个字符");
        }
        String normalizedName = nextName.toLowerCase(Locale.ROOT);
        if (mapper.countByOwnerAndNormalizedNameExcludingId(
                        current.ownerAccountId(), normalizedName, repositoryId)
                > 0) {
            throw new ApiSecurityException(409, "REPOSITORY_NAME_CONFLICT", "该所有者名下已存在同名仓库");
        }
        if (mapper.updateEditableMetadata(
                        repositoryId,
                        nextName,
                        normalizedName,
                        nextDescription,
                        nextBranch == null || nextBranch.isBlank() ? null : nextBranch,
                        expectedVersion)
                != 1) {
            throw new ApiSecurityException(
                    409, "REPOSITORY_VERSION_CONFLICT", "仓库资料已被其他操作修改，请刷新后重试");
        }
        authService.audit(
                actor.id(), null, repositoryId, "REPOSITORY_UPDATED", "SUCCESS", sourceIp);
    }

    private RepositoryRow requireRow(UUID id) {
        RepositoryRow row = mapper.findById(id);
        if (row == null) {
            throw new IllegalArgumentException("仓库不存在");
        }
        return row;
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("仓库名称不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("仓库名称不能超过 100 个字符");
        }
        return normalized;
    }

    public record Metadata(String description, long version) {}
}
