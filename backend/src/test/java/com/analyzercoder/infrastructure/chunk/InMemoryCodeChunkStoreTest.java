package com.analyzercoder.infrastructure.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryCodeChunkStoreTest {
    @Test
    void paginatesRepositoryChunksInFilePathOrder() {
        InMemoryCodeChunkStore store = new InMemoryCodeChunkStore();
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        store.replaceRepositoryChunks(
                repositoryId,
                List.of(
                        chunk(repositoryId, "src/zeta/Last.java", "java", "class Last {}"),
                        chunk(repositoryId, "src/alpha/First.java", "java", "class First {}"),
                        chunk(repositoryId, "README.md", "markdown", "# Project")));
        assertThat(store.findByRepositoryId(repositoryId, 2, 1))
                .extracting(CodeChunk::filePath)
                .containsExactly("src/alpha/First.java", "src/zeta/Last.java");
    }

    @Test
    void searchesChunksCaseInsensitivelyAndRanksFilePathMatchesFirst() {
        InMemoryCodeChunkStore store = new InMemoryCodeChunkStore();
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        store.replaceRepositoryChunks(
                repositoryId,
                List.of(
                        chunk(
                                repositoryId,
                                "src/order/OrderService.java",
                                "java",
                                "class OrderService {}"),
                        chunk(
                                repositoryId,
                                "src/billing/BillingService.java",
                                "java",
                                "void chargeOrder() {}"),
                        chunk(
                                repositoryId,
                                "docs/payments.md",
                                "markdown",
                                "Order payment workflow")));
        assertThat(store.searchByRepositoryId(repositoryId, "ORDER", 10, 0))
                .extracting(CodeChunk::filePath)
                .containsExactly(
                        "src/order/OrderService.java",
                        "docs/payments.md",
                        "src/billing/BillingService.java");
        assertThat(store.countSearchByRepositoryId(repositoryId, "ORDER")).isEqualTo(3);
    }

    private CodeChunk chunk(
            CodeRepositoryId repositoryId, String filePath, String language, String content) {
        return CodeChunk.fileChunk(
                repositoryId,
                RepositorySnapshotId.newId(),
                "test-commit",
                filePath,
                language,
                1,
                1,
                content);
    }
}
