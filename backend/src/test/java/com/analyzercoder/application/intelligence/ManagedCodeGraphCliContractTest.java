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
}
