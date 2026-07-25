package com.analyzercoder.application.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCodeBrowserServiceTest {
    @TempDir Path root;

    @Test
    void listsTheWholeSnapshotAndReadsUtf8Content() throws Exception {
        Files.createDirectories(root.resolve("src/components"));
        Files.writeString(root.resolve("src/components/Panel.vue"), "<template>\n  <main />\n</template>\n");
        Files.writeString(root.resolve("README.md"), "# 示例");
        RepositoryCodeBrowserService service = service(2_000_000);

        var snapshot = service.list(repositoryId());
        assertThat(snapshot.branch()).isEqualTo("main");
        assertThat(snapshot.files()).extracting(RepositoryCodeBrowserService.FileEntry::path)
            .containsExactly("README.md", "src/components/Panel.vue");

        var content = service.read(repositoryId(), "src/components/Panel.vue");
        assertThat(content.language()).isEqualTo("vue");
        assertThat(content.lineCount()).isEqualTo(3);
        assertThat(content.content()).contains("<main />");
    }

    @Test
    void rejectsTraversalBinaryAndOversizedFiles() throws Exception {
        Files.write(root.resolve("binary.dat"), new byte[] {1, 0, 2});
        Files.writeString(root.resolve("large.txt"), "123456");
        RepositoryCodeBrowserService service = service(5);

        assertThatThrownBy(() -> service.read(repositoryId(), "../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("超出");
        assertThatThrownBy(() -> service.read(repositoryId(), "binary.dat"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("二进制");
        assertThatThrownBy(() -> service.read(repositoryId(), "large.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("大小限制");
    }

    private RepositoryCodeBrowserService service(long maxBytes) {
        RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
        when(repositories.get(repositoryId())).thenReturn(repository());
        return new RepositoryCodeBrowserService(repositories, maxBytes);
    }

    private CodeRepository repository() {
        Instant now = Instant.parse("2026-07-25T00:00:00Z");
        return new CodeRepository(
            repositoryId(), "sample", root, RepositorySourceType.LOCAL_GIT, "main", "a".repeat(40),
            "b".repeat(64), false, RepositorySnapshotId.of(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")),
            root, root.resolve(".codegraph"), now, now, now, now
        );
    }

    private static CodeRepositoryId repositoryId() {
        return CodeRepositoryId.of(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }
}
