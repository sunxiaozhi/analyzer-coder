package com.analyzercoder.application.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.application.repository.RepositoryPreparationService;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.persistence.mapper.ProjectHealthMapper;
import com.analyzercoder.infrastructure.persistence.model.ProjectKnowledgeHealthRow;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectHealthOverviewServiceTest {
    private final RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
    private final RepositoryPreparationService preparation =
            mock(RepositoryPreparationService.class);
    private final ProjectHealthMapper health = mock(ProjectHealthMapper.class);
    private final TaskReviewService reviews = mock(TaskReviewService.class);
    private final ProjectHealthOverviewService service =
            new ProjectHealthOverviewService(repositories, preparation, health, reviews);

    @Test
    void reportsReadyOnlyWhenPreparationAndKnowledgeHaveNoOpenIssue() {
        CodeRepository repository = repository();
        when(repositories.get(repository.id())).thenReturn(repository);
        when(preparation.view(repository.id())).thenReturn(preparation("READY", 20, 0, 30));
        when(health.knowledgeHealth(repository.id().value()))
                .thenReturn(new ProjectKnowledgeHealthRow(3, 3, 0, 0, 0, 3, 0, 0));
        when(reviews.list(repository.id(), 5, 0)).thenReturn(List.of());

        ProjectHealthOverviewService.ProjectHealthOverview result =
                service.view(repository.id());

        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.readyForReview()).isTrue();
        assertThat(result.snapshotId()).isEqualTo(repository.currentSnapshotId().value());
        assertThat(result.issues()).isEmpty();
        assertThat(result.knowledge().trusted()).isEqualTo(3);
    }

    @Test
    void exposesDeterministicBlockersAndKnowledgeWarningsWithoutHidingOverlap() {
        CodeRepository repository = repository();
        when(repositories.get(repository.id())).thenReturn(repository);
        when(preparation.view(repository.id())).thenReturn(preparation("NOT_READY", 0, 0, 0));
        when(health.knowledgeHealth(repository.id().value()))
                .thenReturn(new ProjectKnowledgeHealthRow(4, 0, 1, 1, 2, 0, 1, 2));
        when(reviews.list(repository.id(), 5, 0)).thenReturn(List.of());

        ProjectHealthOverviewService.ProjectHealthOverview result =
                service.view(repository.id());

        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.readyForReview()).isFalse();
        assertThat(result.issues())
                .extracting(ProjectHealthOverviewService.HealthIssue::code)
                .containsExactly(
                        "CONTENT_INDEX_NOT_READY",
                        "CODEGRAPH_NOT_READY",
                        "NO_TRUSTED_KNOWLEDGE",
                        "REQUIRED_KNOWLEDGE_WITHOUT_OWNER",
                        "UNREVIEWED_KNOWLEDGE",
                        "SUSPECT_KNOWLEDGE",
                        "STALE_KNOWLEDGE");
        assertThat(result.issues().get(0).severity()).isEqualTo("BLOCKING");
    }

    private static RepositoryPreparationService.PreparationView preparation(
            String state, long chunks, long missing, int graphNodes) {
        return new RepositoryPreparationService.PreparationView(
                CodeRepositoryId.newId().value(),
                state,
                "READY".equals(state) ? 100 : 25,
                "test",
                List.of(),
                new RepositoryPreparationService.ProjectProfile(
                        10,
                        100,
                        chunks,
                        Math.max(0, chunks - missing),
                        missing,
                        3,
                        "SEMANTIC_EMBEDDING",
                        "语义检索",
                        graphNodes,
                        graphNodes * 2,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                null,
                null,
                null);
    }

    private static CodeRepository repository() {
        Instant now = Instant.now();
        return new CodeRepository(
                CodeRepositoryId.newId(),
                "sample",
                Path.of("sample"),
                RepositorySourceType.LOCAL_GIT,
                "main",
                "abc123",
                "digest",
                false,
                RepositorySnapshotId.newId(),
                Path.of("snapshot"),
                Path.of("snapshot/.codegraph"),
                now,
                now,
                now,
                now);
    }
}
