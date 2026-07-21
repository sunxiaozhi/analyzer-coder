package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.RegisterRepositoryCommand;
import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.application.repository.RepositoryScanResult;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final RegisterRepositoryUseCase useCase;
    private final AccessControlService accessControl;

    public RepositoryController(RegisterRepositoryUseCase useCase, AccessControlService accessControl) {
        this.useCase = useCase;
        this.accessControl = accessControl;
    }

    @PostMapping
    public RepositoryResponse register(
        @Valid @RequestBody RegisterRepositoryRequest body,
        HttpServletRequest request
    ) {
        SecurityContext.requireAdmin(request);
        return RepositoryResponse.from(useCase.register(new RegisterRepositoryCommand(body.name(), body.path())));
    }

    @GetMapping("/{repositoryId}")
    public RepositoryResponse get(@PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return RepositoryResponse.from(useCase.get(id));
    }

    @GetMapping
    public List<RepositoryResponse> list(HttpServletRequest request) {
        Set<UUID> visible = accessControl.visibleRepositoryIds(SecurityContext.account(request)).stream().collect(Collectors.toSet());
        return useCase.list().stream().filter(repository -> visible.contains(repository.id().value()))
            .map(RepositoryResponse::from).toList();
    }

    @PostMapping("/{repositoryId}/rescan")
    public RescanRepositoryResponse rescan(@PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.MAINTAIN);
        RepositoryScanResult result = useCase.rescan(id);
        return new RescanRepositoryResponse(result.changed(), RepositoryResponse.from(result.repository()));
    }

    @DeleteMapping("/{repositoryId}")
    public void delete(@PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.MANAGE);
        useCase.delete(id);
    }

    public record RegisterRepositoryRequest(@NotBlank @Size(max = 100) String name, @NotBlank String path) {}
    public record RescanRepositoryResponse(boolean changed, RepositoryResponse repository) {}
    public record RepositoryResponse(
        UUID id, String name, String path, RepositorySourceType sourceType, String branch, String commit,
        String worktreeDigest, boolean dirty, UUID snapshotId, Instant snapshotCreatedAt,
        String codeGraphPath, boolean codeGraphDetected, Instant lastScannedAt
    ) {
        public static RepositoryResponse from(CodeRepository repository) {
            return new RepositoryResponse(
                repository.id().value(), repository.name(), repository.path().toString(), repository.sourceType(),
                repository.defaultBranch(), repository.currentCommit(), repository.worktreeDigest(), repository.worktreeDirty(),
                repository.currentSnapshotId() == null ? null : repository.currentSnapshotId().value(),
                repository.snapshotCreatedAt(), repository.codeGraphPath().toString(),
                Files.isDirectory(repository.codeGraphPath()), repository.lastScannedAt()
            );
        }
    }
}
