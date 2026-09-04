package com.analyzercoder.application.repository;

import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.application.project.EngineeringProjectService;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.RepositoryGovernanceMapper;
import com.analyzercoder.infrastructure.persistence.model.GovernanceAccountRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryGovernanceRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryMemberRow;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryPermission;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处理仓库成员授权、所有权转移和删除申请。
 *
 * <p>所有写操作都在事务中锁定仓库治理记录，并通过所有权版本实现乐观并发控制，避免并发管理操作相互覆盖。
 */
@Service
public class RepositoryGovernanceService {
    private final RepositoryGovernanceMapper mapper;
    private final AccessControlService access;
    private final IndexJobStore indexJobs;
    private final EngineeringProjectService engineeringProjects;

    public RepositoryGovernanceService(
            RepositoryGovernanceMapper mapper,
            AccessControlService access,
            IndexJobStore indexJobs,
            EngineeringProjectService engineeringProjects) {
        this.mapper = mapper;
        this.access = access;
        this.indexJobs = indexJobs;
        this.engineeringProjects = engineeringProjects;
    }

    public List<RepositoryMemberRow> members(AuthenticatedAccount actor, UUID repositoryId) {
        access.require(actor, CodeRepositoryId.of(repositoryId), RepositoryPermission.MAINTAIN);
        return mapper.findMembers(repositoryId);
    }

    public List<GovernanceAccountRow> candidates(AuthenticatedAccount actor, UUID repositoryId) {
        access.requireOwner(actor, CodeRepositoryId.of(repositoryId));
        return mapper.findEnabledAccounts();
    }

    @Transactional
    public long setGrant(
            AuthenticatedAccount actor,
            UUID repositoryId,
            UUID accountId,
            RepositoryPermission permission,
            long expectedVersion,
            String sourceIp) {
        access.requireOwner(actor, CodeRepositoryId.of(repositoryId));
        RepositoryGovernanceRow repository = locked(repositoryId, expectedVersion);
        GovernanceAccountRow target = mapper.findAccount(accountId);
        if (target == null || !target.enabled()) {
            throw new IllegalArgumentException("授权目标必须是启用账号");
        }
        if (accountId.equals(repository.ownerAccountId())) {
            throw new IllegalArgumentException("OWNER 不能写入普通授权");
        }
        if (permission == null) {
            throw new IllegalArgumentException("授权级别不能为空");
        }

        mapper.upsertGrant(repositoryId, accountId, permission.name());
        bump(repositoryId, expectedVersion);
        audit(actor.id(), accountId, repositoryId, "REPOSITORY_PERMISSION_CHANGED", sourceIp);
        return expectedVersion + 1;
    }

    @Transactional
    public long revokeGrant(
            AuthenticatedAccount actor,
            UUID repositoryId,
            UUID accountId,
            long expectedVersion,
            String sourceIp) {
        access.requireOwner(actor, CodeRepositoryId.of(repositoryId));
        RepositoryGovernanceRow repository = locked(repositoryId, expectedVersion);
        if (accountId.equals(repository.ownerAccountId())) {
            throw new IllegalArgumentException("OWNER 不能通过授权接口移除");
        }

        mapper.deleteGrant(repositoryId, accountId);
        bump(repositoryId, expectedVersion);
        audit(actor.id(), accountId, repositoryId, "REPOSITORY_PERMISSION_REVOKED", sourceIp);
        return expectedVersion + 1;
    }

    @Transactional
    public long transfer(
            AuthenticatedAccount actor,
            UUID repositoryId,
            UUID newOwnerId,
            String requestedName,
            RepositoryPermission previousOwnerPermission,
            long expectedVersion,
            String sourceIp) {
        access.requireOwner(actor, CodeRepositoryId.of(repositoryId));
        RepositoryGovernanceRow repository = locked(repositoryId, expectedVersion);
        GovernanceAccountRow target = mapper.findAccount(newOwnerId);
        if (target == null || !target.enabled()) {
            throw new IllegalArgumentException("新 OWNER 必须是启用账号");
        }
        if (newOwnerId.equals(repository.ownerAccountId())) {
            throw new IllegalArgumentException("新 OWNER 不能与当前 OWNER 相同");
        }

        String nextName =
                requestedName == null || requestedName.isBlank()
                        ? repository.repositoryName()
                        : requestedName.trim();
        String normalizedName = nextName.toLowerCase(Locale.ROOT);
        if (mapper.countNameConflict(newOwnerId, normalizedName, repositoryId) > 0) {
            throw new IllegalStateException("目标 OWNER 下已存在同名仓库，请提供新名称");
        }

        mapper.deleteGrant(repositoryId, newOwnerId);
        if (mapper.transferOwnership(
                        repositoryId, expectedVersion, newOwnerId, nextName, normalizedName)
                != 1) {
            throw new IllegalStateException("仓库状态或所有权版本已变化，请刷新后重试");
        }
        mapper.deleteGrant(repositoryId, repository.ownerAccountId());
        if (previousOwnerPermission != null) {
            mapper.upsertGrant(
                    repositoryId, repository.ownerAccountId(), previousOwnerPermission.name());
        }
        audit(actor.id(), newOwnerId, repositoryId, "REPOSITORY_OWNERSHIP_TRANSFERRED", sourceIp);
        return expectedVersion + 1;
    }

    @Transactional
    public void requestDeletion(AuthenticatedAccount actor, UUID repositoryId, String sourceIp) {
        access.requireOwner(actor, CodeRepositoryId.of(repositoryId));
        RepositoryGovernanceRow repository = mapper.findForUpdate(repositoryId);
        if (repository == null) {
            throw new IllegalArgumentException("仓库不存在");
        }
        if (indexJobs.hasActiveJob(CodeRepositoryId.of(repositoryId))) {
            throw new IllegalStateException("仓库存在运行中的写任务，暂不能删除");
        }
        engineeringProjects.requireRepositoryNotLinked(repositoryId);

        Instant now = Instant.now();
        if (mapper.markDeleting(repositoryId, repository.ownershipVersion()) != 1) {
            throw new IllegalStateException("仓库治理状态已变化，请刷新后重试");
        }
        mapper.insertDeletionTombstone(repositoryId, actor.id(), now);
        audit(actor.id(), null, repositoryId, "REPOSITORY_DELETION_REQUESTED", sourceIp);
    }

    private RepositoryGovernanceRow locked(UUID repositoryId, long expectedVersion) {
        RepositoryGovernanceRow row = mapper.findForUpdate(repositoryId);
        if (row == null) {
            throw new IllegalArgumentException("仓库不存在");
        }
        if (row.ownershipVersion() != expectedVersion) {
            throw new IllegalStateException("所有权版本已变化，请刷新后重试");
        }
        return row;
    }

    private void bump(UUID repositoryId, long expectedVersion) {
        if (mapper.incrementVersion(repositoryId, expectedVersion) != 1) {
            throw new IllegalStateException("所有权版本已变化，请刷新后重试");
        }
    }

    private void audit(
            UUID actorId,
            UUID targetAccountId,
            UUID repositoryId,
            String eventType,
            String sourceIp) {
        mapper.insertAudit(
                UUID.randomUUID(),
                actorId,
                targetAccountId,
                repositoryId,
                eventType,
                UUID.randomUUID(),
                sourceIp,
                Instant.now());
    }
}
