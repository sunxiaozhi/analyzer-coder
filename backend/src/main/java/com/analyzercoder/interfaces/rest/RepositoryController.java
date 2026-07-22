package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.repository.*;
import com.analyzercoder.domain.repository.*;
import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.analyzercoder.security.*;
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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final RegisterRepositoryUseCase useCase;
    private final AccessControlService accessControl;
    private final RepositoryGovernanceService governance;
    private final CodeGraphArtifactMapper codeGraphArtifacts;
    public RepositoryController(RegisterRepositoryUseCase useCase,AccessControlService accessControl,
        RepositoryGovernanceService governance,CodeGraphArtifactMapper codeGraphArtifacts){
        this.useCase=useCase;this.accessControl=accessControl;this.governance=governance;this.codeGraphArtifacts=codeGraphArtifacts;
    }

    @PostMapping
    public RepositoryResponse register(@Valid @RequestBody RegisterRepositoryRequest body,HttpServletRequest request){
        var account=SecurityContext.account(request);
        CodeRepository repository=useCase.register(new RegisterRepositoryCommand(body.name(),body.path(),account.id()));
        return response(repository,account);
    }
    @GetMapping("/{repositoryId}")
    public RepositoryResponse get(@PathVariable UUID repositoryId,HttpServletRequest request){
        var account=SecurityContext.account(request);CodeRepositoryId id=CodeRepositoryId.of(repositoryId);
        accessControl.require(account,id,RepositoryPermission.READ);return response(useCase.get(id),account);
    }
    @GetMapping
    public List<RepositoryResponse> list(HttpServletRequest request){
        var account=SecurityContext.account(request);Set<UUID> visible=accessControl.visibleRepositoryIds(account).stream().collect(Collectors.toSet());
        return useCase.list().stream().filter(r->visible.contains(r.id().value())).map(r->response(r,account)).toList();
    }
    @PostMapping("/{repositoryId}/rescan")
    public RescanRepositoryResponse rescan(@PathVariable UUID repositoryId,HttpServletRequest request){
        var account=SecurityContext.account(request);CodeRepositoryId id=CodeRepositoryId.of(repositoryId);
        accessControl.require(account,id,RepositoryPermission.MAINTAIN);RepositoryScanResult result=useCase.rescan(id);
        return new RescanRepositoryResponse(result.changed(),response(result.repository(),account));
    }
    @DeleteMapping("/{repositoryId}")
    public void delete(@PathVariable UUID repositoryId,HttpServletRequest request){
        var account=SecurityContext.account(request);governance.requestDeletion(account,repositoryId,request.getRemoteAddr());
    }
    private RepositoryResponse response(CodeRepository repository,AuthenticatedAccount account){
        CodeGraphArtifactRow artifact=repository.currentSnapshotId()==null?null:
            codeGraphArtifacts.findPublished(repository.id().value(),repository.currentSnapshotId().value());
        return RepositoryResponse.from(repository,accessControl.describe(account,repository.id()),artifact);
    }

    public record RegisterRepositoryRequest(@NotBlank @Size(max=100) String name,@NotBlank String path){}
    public record RescanRepositoryResponse(boolean changed,RepositoryResponse repository){}
    public record RepositoryResponse(
        UUID id,String name,String path,RepositorySourceType sourceType,String branch,String commit,String worktreeDigest,
        boolean dirty,UUID snapshotId,Instant snapshotCreatedAt,String codeGraphPath,boolean codeGraphDetected,
        Instant lastScannedAt,UUID ownerAccountId,String ownerDisplayName,String relationship,long ownershipVersion,
        String repositoryStatus,RepositoryAccess.Capabilities capabilities
    ){
        static RepositoryResponse from(CodeRepository repository,RepositoryAccess access){return from(repository,access,null);}
        static RepositoryResponse from(CodeRepository repository,RepositoryAccess access,CodeGraphArtifactRow artifact){return new RepositoryResponse(
            repository.id().value(),repository.name(),repository.path().toString(),repository.sourceType(),repository.defaultBranch(),
            repository.currentCommit(),repository.worktreeDigest(),repository.worktreeDirty(),repository.currentSnapshotId()==null?null:repository.currentSnapshotId().value(),
            repository.snapshotCreatedAt(),artifact==null?repository.codeGraphPath().toString():artifact.artifactPath(),artifact!=null,repository.lastScannedAt(),
            access.ownerAccountId(),access.ownerDisplayName(),access.relationship(),access.ownershipVersion(),access.repositoryStatus(),access.capabilities());}
    }
}
