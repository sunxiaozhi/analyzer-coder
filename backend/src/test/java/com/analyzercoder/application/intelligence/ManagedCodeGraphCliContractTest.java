package com.analyzercoder.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.analyzercoder.infrastructure.persistence.mapper.CodeGraphArtifactMapper;
import com.analyzercoder.infrastructure.persistence.model.CodeGraphArtifactRow;
import com.analyzercoder.infrastructure.persistence.model.RepositoryVersionRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

@EnabledOnOs(OS.LINUX)
class ManagedCodeGraphCliContractTest {
    @TempDir Path temporaryDirectory;

    @Test
    void invokesVersionAndInitContractAndPublishesGeneratedArtifact() throws Exception {
        Path executable = temporaryDirectory.resolve("fake-codegraph");
        Files.writeString(
                executable,
                """
                #!/usr/bin/env sh
                set -eu
                if [ "$1" = "--version" ]; then
                  echo "codegraph-contract-1.0"
                  exit 0
                fi
                if [ "$1" = "init" ]; then
                  mkdir -p "$2/.codegraph"
                  printf '3 nodes\n2 edges\n'
                  exit 0
                fi
                echo "unexpected arguments: $*" >&2
                exit 23
                """);
        Files.setPosixFilePermissions(
                executable,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Path snapshot = temporaryDirectory.resolve("snapshot");
        Files.createDirectories(snapshot);
        Files.writeString(snapshot.resolve("Example.java"), "class Example {}\n");

        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        CodeGraphArtifactMapper mapper = mock(CodeGraphArtifactMapper.class);
        when(mapper.findRepositoryVersion(repositoryId))
                .thenReturn(new RepositoryVersionRow(snapshotId, snapshot.toString()));
        CodeGraphArtifactPublisher publisher = mock(CodeGraphArtifactPublisher.class);
        ManagedCodeGraphService service =
                new ManagedCodeGraphService(
                        mapper,
                        new ObjectMapper(),
                        executable.toString(),
                        1,
                        temporaryDirectory.resolve("artifacts").toString(),
                        publisher);

        CodeGraphService.Artifact artifact = service.build(repositoryId);

        assertThat(artifact.snapshotId()).isEqualTo(snapshotId);
        assertThat(artifact.cliVersion()).isEqualTo("codegraph-contract-1.0");
        assertThat(artifact.nodeCount()).isEqualTo(3);
        assertThat(artifact.edgeCount()).isEqualTo(2);
        assertThat(Path.of(artifact.artifactPath())).isDirectory();
        assertThat(Path.of(artifact.artifactPath()).getParent().resolve("Example.java"))
                .hasContent("class Example {}\n");
        ArgumentCaptor<CodeGraphArtifactRow> row =
                ArgumentCaptor.forClass(CodeGraphArtifactRow.class);
        verify(publisher).publish(row.capture());
        assertThat(row.getValue().snapshotId()).isEqualTo(snapshotId);
        verify(mapper, org.mockito.Mockito.atLeast(2)).findRepositoryVersion(repositoryId);
    }

    @Test
    void combinesImpactAndExportContractsWithoutInventingStarEdges() throws Exception {
        Path executable = temporaryDirectory.resolve("fake-codegraph-query");
        Files.writeString(
                executable,
                """
                #!/usr/bin/env sh
                set -eu
                if [ "$1" = "impact" ]; then
                  printf '%s\n' '{"symbol":"charge","depth":3,"nodeCount":3,"edgeCount":2,"affected":[{"name":"Service","filePath":"src/Service.java","startLine":10},{"name":"Controller","filePath":"src/Controller.java","startLine":20}]}'
                  exit 0
                fi
                if [ "$1" = "export" ]; then
                  printf '%s\n' '{"nodes":[{"id":"focus","label":"charge","kind":"method","source_file":"src/Gateway.java","start_line":4},{"id":"service","label":"Service","kind":"class","source_file":"src/Service.java","start_line":10},{"id":"controller","label":"Controller","kind":"class","source_file":"src/Controller.java","start_line":20}],"edges":[{"source":"service","target":"focus","relation":"calls","line":14},{"source":"controller","target":"service","relation":"references","line":28}]}'
                  exit 0
                fi
                echo "unexpected arguments: $*" >&2
                exit 23
                """);
        Files.setPosixFilePermissions(
                executable,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));

        UUID repositoryId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        Path artifactRoot = temporaryDirectory.resolve("query-artifacts").toAbsolutePath();
        Path marker =
                artifactRoot
                        .resolve(repositoryId.toString())
                        .resolve("codegraph")
                        .resolve(snapshotId.toString())
                        .resolve(artifactId.toString())
                        .resolve("project/.codegraph");
        Files.createDirectories(marker);
        CodeGraphArtifactMapper mapper = mock(CodeGraphArtifactMapper.class);
        when(mapper.findRepositoryVersion(repositoryId))
                .thenReturn(
                        new RepositoryVersionRow(
                                snapshotId, temporaryDirectory.resolve("snapshot").toString()));
        when(mapper.findPublished(repositoryId, snapshotId))
                .thenReturn(
                        new CodeGraphArtifactRow(
                                artifactId,
                                repositoryId,
                                snapshotId,
                                "codegraph-contract-1.0",
                                "PUBLISHED",
                                marker.toString(),
                                3,
                                2));
        ManagedCodeGraphService service =
                new ManagedCodeGraphService(
                        mapper,
                        new ObjectMapper(),
                        executable.toString(),
                        1,
                        artifactRoot.toString(),
                        mock(CodeGraphArtifactPublisher.class));

        CodeGraphPropagation result = service.impact(repositoryId, "charge", 3);

        assertThat(result.paths())
                .filteredOn(path -> path.targetNodeId().equals("controller"))
                .singleElement()
                .satisfies(
                        path ->
                                assertThat(path.nodeIds())
                                        .containsExactly("focus", "service", "controller"));
        assertThat(result.edges())
                .extracting(CodeGraphPropagation.Edge::source, CodeGraphPropagation.Edge::target)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("service", "focus"),
                        org.assertj.core.groups.Tuple.tuple("controller", "service"));
    }
}
