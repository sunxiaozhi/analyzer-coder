package com.analyzercoder.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.application.repository.RegisterRepositoryUseCase;
import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.review.TaskReviewService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.chunk.InMemoryCodeChunkStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectContextPackServiceTest {

    @Test
    void includesRulesAndRelevantCodeWithCurrentVersionMetadata() {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        InMemoryCodeChunkStore chunks = new InMemoryCodeChunkStore();
        CodeRepository repository = repository();
        when(repositories.get(repository.id())).thenReturn(repository);
        chunks.replaceRepositoryChunks(
                repository.id(),
                List.of(
                        chunk(
                                repository,
                                "src/OrderService.java",
                                "java",
                                RepositoryAssetType.CODE,
                                "class OrderService { void createOrder() {} }")));

        IntelligenceService intelligence = mock(IntelligenceService.class);
        when(intelligence.cards(repository.id().value(), false)).thenReturn(List.of());
        when(intelligence.reviewKnowledgeReferences(
                        repository.id().value(), "修改 OrderService 创建订单流程", 10))
                .thenReturn(List.of());
        TaskContextService taskContexts =
                new TaskContextService(
                        repositories, chunks, intelligence, mock(TaskReviewService.class));
        ProjectContextPackService service = new ProjectContextPackService(taskContexts);
        ProjectContextPackService.ContextPack result =
                service.generate(repository.id(), "修改 OrderService 创建订单流程", 10, 8000);

        assertThat(result.commitSha()).isEqualTo("abc123");
        assertThat(result.snapshotId()).isEqualTo(repository.currentSnapshotId().value());
        assertThat(result.items())
                .extracting(ProjectContextPackService.ContextItem::assetType)
                .contains(RepositoryAssetType.TASK, RepositoryAssetType.CODE);
        assertThat(result.items().get(0).sourceType()).isEqualTo("UNKNOWN");
        assertThat(result.markdown())
                .contains("Agent Task Context", "OrderService.java", "abc123", "检索候选不能产生工程义务");
    }

    private static CodeChunk chunk(
            CodeRepository repository,
            String path,
            String language,
            RepositoryAssetType type,
            String content) {
        return CodeChunk.fileChunk(
                repository.id(),
                repository.currentSnapshotId(),
                repository.currentCommit(),
                path,
                language,
                type,
                1,
                1,
                content);
    }

    private static CodeRepository repository() {
        Instant now = Instant.now();
        return new CodeRepository(
                CodeRepositoryId.newId(),
                "orders",
                Path.of("orders"),
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
