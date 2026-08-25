package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.common.PageResult;
import com.analyzercoder.application.indexing.IndexJobPageService;
import com.analyzercoder.application.indexing.IndexJobUseCase;
import com.analyzercoder.application.indexing.StartIndexCommand;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.security.AccessControlService;
import com.analyzercoder.security.RepositoryPermission;
import com.analyzercoder.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前模块相关 HTTP 接口，负责请求参数绑定并将已认证的调用委派给应用服务。 */
@RestController
public class IndexController {
    private final IndexJobUseCase useCase;
    private final AccessControlService accessControl;
    private final IndexJobPageService pageService;

    public IndexController(
            IndexJobUseCase useCase,
            AccessControlService accessControl,
            IndexJobPageService pageService) {
        this.useCase = useCase;
        this.accessControl = accessControl;
        this.pageService = pageService;
    }

    @PostMapping("/api/repositories/{repositoryId}/index")
    public IndexJobResponse start(
            @PathVariable UUID repositoryId,
            @RequestBody(required = false) StartIndexRequest body,
            HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.MAINTAIN);
        IndexJobType type = body == null || body.type() == null ? IndexJobType.FULL : body.type();
        return IndexJobResponse.from(useCase.start(new StartIndexCommand(id, type)));
    }

    @GetMapping("/api/repositories/{repositoryId}/index/status")
    public IndexJobResponse latest(@PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return IndexJobResponse.from(useCase.getLatestStatus(id));
    }

    @GetMapping("/api/index-jobs/page")
    public PageResult<IndexJobResponse> page(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int pageNum,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        var account = SecurityContext.account(request);
        return pageService.page(account, pageNum, pageSize).map(IndexJobResponse::from);
    }

    @GetMapping("/api/index-jobs/{jobId}")
    public IndexJobResponse get(@PathVariable UUID jobId, HttpServletRequest request) {
        IndexJob job = useCase.get(IndexJobId.of(jobId));
        accessControl.require(
                SecurityContext.account(request), job.repositoryId(), RepositoryPermission.READ);
        return IndexJobResponse.from(job);
    }

    @GetMapping("/api/index-jobs")
    public List<IndexJobResponse> list(HttpServletRequest request) {
        Set<UUID> visible =
                accessControl.visibleRepositoryIds(SecurityContext.account(request)).stream()
                        .collect(Collectors.toSet());
        return useCase.list(null).stream()
                .filter(job -> visible.contains(job.repositoryId().value()))
                .map(IndexJobResponse::from)
                .toList();
    }

    @GetMapping("/api/repositories/{repositoryId}/index-jobs")
    public List<IndexJobResponse> listForRepository(
            @PathVariable UUID repositoryId, HttpServletRequest request) {
        CodeRepositoryId id = CodeRepositoryId.of(repositoryId);
        accessControl.require(SecurityContext.account(request), id, RepositoryPermission.READ);
        return useCase.list(id).stream().map(IndexJobResponse::from).toList();
    }

    @PostMapping("/api/index-jobs/{jobId}/cancel")
    public IndexJobResponse cancel(@PathVariable UUID jobId, HttpServletRequest request) {
        IndexJob job = useCase.get(IndexJobId.of(jobId));
        accessControl.require(
                SecurityContext.account(request),
                job.repositoryId(),
                RepositoryPermission.MAINTAIN);
        return IndexJobResponse.from(useCase.cancel(job.id()));
    }

    @PostMapping("/api/index-jobs/{jobId}/retries")
    public IndexJobResponse retry(@PathVariable UUID jobId, HttpServletRequest request) {
        IndexJob job = useCase.get(IndexJobId.of(jobId));
        accessControl.require(
                SecurityContext.account(request),
                job.repositoryId(),
                RepositoryPermission.MAINTAIN);
        return IndexJobResponse.from(useCase.retry(job.id()));
    }

    public record StartIndexRequest(IndexJobType type) {}

    public record IndexJobResponse(
            UUID id,
            UUID repositoryId,
            IndexJobType type,
            IndexJobStatus status,
            String currentStep,
            String executionMode,
            String fallbackReason,
            String failureCode,
            String errorMessage,
            Instant startedAt,
            Instant heartbeatAt,
            Instant timeoutAt,
            Instant finishedAt,
            Instant createdAt) {
        public static IndexJobResponse from(IndexJob job) {
            return new IndexJobResponse(
                    job.id().value(),
                    job.repositoryId().value(),
                    job.type(),
                    job.status(),
                    job.currentStep(),
                    job.executionMode(),
                    job.fallbackReason(),
                    job.failureCode(),
                    job.errorMessage(),
                    job.startedAt(),
                    job.heartbeatAt(),
                    job.timeoutAt(),
                    job.finishedAt(),
                    job.createdAt());
        }
    }
}
