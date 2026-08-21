package com.analyzercoder.application.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.CodeChunkMapper;
import com.analyzercoder.infrastructure.persistence.model.ModuleSymbolRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectArchitectureSymbolServiceTest {

    @Test
    void returnsCurrentSnapshotSymbolsForValidatedLayerModule() {
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        CodeChunkMapper chunks = mock(CodeChunkMapper.class);
        ProjectArchitectureSymbolService service =
                new ProjectArchitectureSymbolService(architecture, chunks);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        UUID snapshotId = UUID.randomUUID();
        when(architecture.map(repositoryId))
                .thenReturn(map(repositoryId, snapshotId, "backend/domain"));
        when(chunks.findModuleSymbols(
                        repositoryId.value(), snapshotId, "backend", "domain", false, 3))
                .thenReturn(
                        List.of(
                                symbol("Order", "CLASS", "backend/src/main/java/acme/domain/Order.java", 3),
                                symbol("create", "METHOD", "backend/src/main/java/acme/domain/Order.java", 12),
                                symbol("cancel", "METHOD", "backend/src/main/java/acme/domain/Order.java", 24)));

        ProjectArchitectureSymbolService.ModuleSymbols result =
                service.symbols(repositoryId, "backend/domain", 2);

        assertThat(result.snapshotId()).isEqualTo(snapshotId.toString());
        assertThat(result.module()).isEqualTo("backend/domain");
        assertThat(result.symbols())
                .extracting(ProjectArchitectureSymbolService.ModuleSymbol::symbolName)
                .containsExactly("Order", "create");
        assertThat(result.truncated()).isTrue();
        verify(chunks)
                .findModuleSymbols(
                        repositoryId.value(), snapshotId, "backend", "domain", false, 3);
    }

    @Test
    void rejectsUnknownModuleBeforeQueryingChunks() {
        ProjectArchitectureMapService architecture = mock(ProjectArchitectureMapService.class);
        CodeChunkMapper chunks = mock(CodeChunkMapper.class);
        ProjectArchitectureSymbolService service =
                new ProjectArchitectureSymbolService(architecture, chunks);
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        when(architecture.map(repositoryId))
                .thenReturn(map(repositoryId, UUID.randomUUID(), "frontend/api"));

        assertThatThrownBy(() -> service.symbols(repositoryId, "../secrets", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有效");
    }

    private static ProjectArchitectureMapService.ArchitectureMap map(
            CodeRepositoryId repositoryId, UUID snapshotId, String module) {
        return new ProjectArchitectureMapService.ArchitectureMap(
                repositoryId.value().toString(),
                snapshotId.toString(),
                "abc123",
                Instant.parse("2026-08-21T00:00:00Z"),
                List.of(
                        new ProjectArchitectureMapService.ArchitectureNode(
                                module, "domain", module, "MODULE", 2, 2, "java", null)),
                List.of(),
                List.of(),
                new ProjectArchitectureMapService.AnalysisCoverage(
                        2, 2, 0, 0, 0, false, List.of()));
    }

    private static ModuleSymbolRow symbol(
            String name, String kind, String path, int startLine) {
        return new ModuleSymbolRow(name, kind, path, startLine, startLine + 4, "java");
    }
}
