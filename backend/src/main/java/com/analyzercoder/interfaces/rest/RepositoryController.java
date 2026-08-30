package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.application.repository.RegisterRepositoryCommand;
import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.application.repository.RepositoryEditingService;
import com.analyzercoder.application.repository.RepositoryGovernanceService;
import com.analyzercoder.application.repository.RepositoryPageService;
import com.analyzercoder.application.repository.RepositoryPreparationService;
import com.analyzercoder.application.repository.RepositoryRemoteSyncService;
import com.analyzercoder.application.repository.RepositoryScanResult;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.AuthenticatedAccount;
import com.analyzercoder.security.RepositoryAccess;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供代码仓库相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final RegisterRepositoryUseCase useCase;
    private final AccessControlService accessControl;
    private final RepositoryGovernanceService governance;
    private final RepositoryEditingService editing;
    private final RepositoryPageService pageService;
    private final CodeGraphArtifactMapper codeGraphArtifacts;
    private final RepositoryRemoteSyncService remoteSync;
    private final RepositoryPreparationService preparation;

    public RepositoryController(
            RegisterRepositoryUseCase useCase,
            AccessControlService accessControl,
            RepositoryGovernanceService governance,
            RepositoryEditingService editing,
            RepositoryPageService pageService,
            CodeGraphArtifactMapper codeGraphArtifacts,
            RepositoryRemoteSyncService remoteSync,
            RepositoryPreparationService preparation) {
        this.useCase = useCase;
        this.accessControl = accessControl;
        this.governance = governance;
        this.editing = editing;
        this.pageService = pageService;
        this.codeGraphArtifacts = codeGraphArtifacts;
        this.remoteSync = remoteSync;
        this.preparation = preparation;
    }

    @PostMapping
    public RepositoryResponse register(
            @Valid @RequestBody RegisterRepositoryRequest body, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        CodeRepository repository =
                useCase.register(
                        new RegisterRepositoryCommand(body.name(), body.path(), account.id()));
        return response(repository, account);
    }

    @GetMapping("/page")
    public PageResult<RepositoryResponse> page(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        var account = SecurityContext.account(request);
        return pageService
                .page(account, query, pageNum, pageSize)
                .map(repository -> response(repository, account));
    }

    @GetMapping("/{repositoryId}")
    public RepositoryResponse get(@PathVariable UUID repositoryId, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(account, id, RepositoryPermission.READ);
        return response(useCase.get(id), account);
    }

    @GetMapping
    public List<RepositoryResponse> list(HttpServletRequest request) {
        var account = SecurityContext.account(request);
        Set<UUID> visible =
                accessControl.visibleRepositoryIds(account).stream().collect(Collectors.toSet());
        return useCase.list().stream()
                .filter(r -> visible.contains(r.id().value()))
                .map(r -> response(r, account))
                .toList();
    }

    @PostMapping("/{repositoryId}/rescan")
    public RescanRepositoryResponse rescan(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(account, id, RepositoryPermission.MAINTAIN);
        RepositoryScanResult result = useCase.rescan(id);
        return new RescanRepositoryResponse(
                result.changed(), response(result.repository(), account));
    }

    @PostMapping("/{repositoryId}/sync")
    public RemoteSyncResponse sync(@PathVariable UUID repositoryId, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        var result = remoteSync.sync(account, CodeRepositoryId.of(repositoryId));
        return new RemoteSyncResponse(
                result.changed(), response(result.repository(), account), result.indexJobId());
    }

    @GetMapping("/{repositoryId}/profile")
    public RepositoryPreparationService.PreparationView profile(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        var id = CodeRepositoryId.of(repositoryId);
        accessControl.require(account, id, RepositoryPermission.READ);
        return preparation.view(id);
    }

    @PostMapping("/{repositoryId}/prepare")
    public RepositoryPreparationService.PreparationView prepare(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        var id = CodeRepositoryId.of(repositoryId);
        accessControl.require(account, id, RepositoryPermission.MAINTAIN);
        return preparation.prepare(account, id);
    }

    @PostMapping("/{repositoryId}/prepare/stages/{stageKey}/retry")
    public RepositoryPreparationService.PreparationView retryPreparationStage(
            @PathVariable UUID repositoryId,
            @PathVariable String stageKey,
            HttpServletRequest request) {
        var account = SecurityContext.account(request);
        var id = CodeRepositoryId.of(repositoryId);
        accessControl.require(account, id, RepositoryPermission.MAINTAIN);
        return preparation.retryStage(account, id, stageKey);
    }

    @PatchMapping("/{repositoryId}")
    public RepositoryResponse update(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody UpdateRepositoryRequest body,
            HttpServletRequest request) {
        var account = SecurityContext.account(request);
        editing.update(
                account,
                repositoryId,
                body.name(),
                body.description(),
                body.defaultBranch(),
                body.version(),
                request.getRemoteAddr());
        return response(useCase.get(CodeRepositoryId.of(repositoryId)), account);
    }

    @DeleteMapping("/{repositoryId}")
    public void delete(@PathVariable UUID repositoryId, HttpServletRequest request) {
        var account = SecurityContext.account(request);
        governance.requestDeletion(account, repositoryId, request.getRemoteAddr());
    }

    private RepositoryResponse response(CodeRepository repository, AuthenticatedAccount account) {
        CodeGraphArtifactRow artifact =
                repository.currentSnapshotId() == null
                        ? null
                        : codeGraphArtifacts.findPublished(
                                repository.id().value(), repository.currentSnapshotId().value());
        var metadata = editing.metadata(repository.id().value());
        return RepositoryResponse.from(
                repository, accessControl.describe(account, repository.id()), artifact, metadata);
    }

    public record RegisterRepositoryRequest(
            @NotBlank @Size(max = 100) String name, @NotBlank String path) {}

    public record UpdateRepositoryRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            String defaultBranch,
            long version) {}

    public record RescanRepositoryResponse(boolean changed, RepositoryResponse repository) {}

    public record RemoteSyncResponse(
            boolean changed, RepositoryResponse repository, UUID indexJobId) {}

    public record RepositoryResponse(
            UUID id,
            String name,
            String description,
            long version,
            String path,
            RepositorySourceType sourceType,
            String branch,
            String commit,
            String worktreeDigest,
            boolean dirty,
            UUID snapshotId,
            Instant snapshotCreatedAt,
            String codeGraphPath,
            boolean codeGraphDetected,
            Instant lastScannedAt,
            UUID ownerAccountId,
            String ownerDisplayName,
            String relationship,
            long ownershipVersion,
            String repositoryStatus,
            RepositoryAccess.Capabilities capabilities) {
        static RepositoryResponse from(CodeRepository repository, RepositoryAccess access) {
            return from(repository, access, null, new RepositoryEditingService.Metadata("", 1));
        }

        static RepositoryResponse from(
                CodeRepository repository,
                RepositoryAccess access,
                CodeGraphArtifactRow artifact,
                RepositoryEditingService.Metadata metadata) {
            return new RepositoryResponse(
                    repository.id().value(),
                    repository.name(),
                    metadata.description(),
                    metadata.version(),
                    repository.path().toString(),
                    repository.sourceType(),
                    repository.defaultBranch(),
                    repository.currentCommit(),
                    repository.worktreeDigest(),
                    repository.worktreeDirty(),
                    repository.currentSnapshotId() == null
                            ? null
                            : repository.currentSnapshotId().value(),
                    repository.snapshotCreatedAt(),
                    artifact == null
                            ? repository.codeGraphPath().toString()
                            : artifact.artifactPath(),
                    artifact != null,
                    repository.lastScannedAt(),
                    access.ownerAccountId(),
                    access.ownerDisplayName(),
                    access.relationship(),
                    access.ownershipVersion(),
                    access.repositoryStatus(),
                    access.capabilities());
        }
    }
}
