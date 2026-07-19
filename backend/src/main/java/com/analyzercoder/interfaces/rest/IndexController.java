package com.analyzercoder.interfaces.rest;

import com.analyzercoder.application.indexing.IndexJobUseCase;
import com.analyzercoder.application.indexing.StartIndexCommand;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IndexController {

    private final IndexJobUseCase indexJobUseCase;

    public IndexController(IndexJobUseCase indexJobUseCase) {
        this.indexJobUseCase = indexJobUseCase;
    }

    @PostMapping("/api/repositories/{repositoryId}/index")
    public IndexJobResponse start(@PathVariable UUID repositoryId, @RequestBody(required = false) StartIndexRequest request) {
        IndexJobType type = request == null || request.type() == null ? IndexJobType.FULL : request.type();
        IndexJob indexJob = indexJobUseCase.start(new StartIndexCommand(CodeRepositoryId.of(repositoryId), type));
        return IndexJobResponse.from(indexJob);
    }

    @GetMapping("/api/repositories/{repositoryId}/index/status")
    public IndexJobResponse getLatestStatus(@PathVariable UUID repositoryId) {
        return IndexJobResponse.from(indexJobUseCase.getLatestStatus(CodeRepositoryId.of(repositoryId)));
    }

    @GetMapping("/api/index-jobs/{indexJobId}")
    public IndexJobResponse get(@PathVariable UUID indexJobId) {
        return IndexJobResponse.from(indexJobUseCase.get(IndexJobId.of(indexJobId)));
    }

    public record StartIndexRequest(IndexJobType type) {
    }

    public record IndexJobResponse(
        UUID id,
        UUID repositoryId,
        IndexJobType type,
        IndexJobStatus status,
        String currentStep,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
    ) {

        public static IndexJobResponse from(IndexJob indexJob) {
            return new IndexJobResponse(
                indexJob.id().value(),
                indexJob.repositoryId().value(),
                indexJob.type(),
                indexJob.status(),
                indexJob.currentStep(),
                indexJob.errorMessage(),
                indexJob.startedAt(),
                indexJob.finishedAt(),
                indexJob.createdAt()
            );
        }
    }
}
