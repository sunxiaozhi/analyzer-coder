package com.analyzercoder.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.analyzercoder.application.change.GitChangeRequest;
import com.analyzercoder.application.change.RepositoryChange;
import com.analyzercoder.application.change.RepositoryChangeService;
import com.analyzercoder.application.code.CodeSymbolExtractor;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.indexing.RepositoryAssetType;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import com.analyzercoder.infrastructure.chunk.InMemoryCodeChunkStore;
import com.analyzercoder.infrastructure.git.ProcessGitClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangedSymbolResolverTest {
    @TempDir Path workspace;

    private final ProcessGitClient gitClient = new ProcessGitClient();
    private final RepositoryChangeService changes = new RepositoryChangeService(gitClient);
    private final CodeSymbolExtractor extractor = new CodeSymbolExtractor();
    private Path repositoryRoot;
    private String baseCommit;

    @BeforeEach
    void initializeRepository() throws Exception {
        repositoryRoot = Files.createDirectory(workspace.resolve("repository"));
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test User");
        write(
                "src/AccountService.java",
                """
                public class AccountService {
                    public String findAccount(String id) {
                        return id;
                    }
                }
                """);
        write(
                "src/LegacyService.java",
                """
                public class LegacyService {
                    public void removeMe() {
                    }
                }
                """);
        git("add", "-A");
        git("commit", "-m", "initial");
        baseCommit = git("rev-parse", "HEAD").trim();
    }

    @Test
    void mapsCommitHunksToRealSourceDeclarations() throws Exception {
        write(
                "src/AccountService.java",
                """
                public class AccountService {
                    public String findAccount(String id) {
                        return id == null ? "missing" : id;
                    }
                }
                """);
        git("add", "-A");
        git("commit", "-m", "modify method");
        String headCommit = git("rev-parse", "HEAD").trim();
        RepositoryChange change =
                changes.analyze(
                        GitChangeRequest.commitRange(repositoryRoot, baseCommit, headCommit));

        ChangedSymbolResolver.ResolutionResult result =
                resolver(new InMemoryCodeChunkStore()).resolve(repository(headCommit), change);

        assertThat(result.symbols())
                .filteredOn(symbol -> symbol.filePath().equals("src/AccountService.java"))
                .extracting(
                        ChangedSymbolResolver.ChangedSymbol::name,
                        ChangedSymbolResolver.ChangedSymbol::kind,
                        ChangedSymbolResolver.ChangedSymbol::resolution)
                .containsExactly(
                        tuple(
                                "findAccount",
                                "METHOD",
                                ChangedSymbolResolver.Resolution.SOURCE_DECLARATION));
        assertThat(result.symbols().get(0).provenance())
                .allMatch(
                        provenance ->
                                provenance.sourceType()
                                        == ChangedSymbolResolver.ProvenanceType.SOURCE_TEXT);
    }

    @Test
    void deletedFileKeepsSymbolsFromTheBaseCommit() throws Exception {
        Files.delete(repositoryRoot.resolve("src/LegacyService.java"));
        git("add", "-A");
        git("commit", "-m", "delete legacy service");
        String headCommit = git("rev-parse", "HEAD").trim();
        RepositoryChange change =
                changes.analyze(
                        GitChangeRequest.commitRange(repositoryRoot, baseCommit, headCommit));

        ChangedSymbolResolver.ResolutionResult result =
                resolver(new InMemoryCodeChunkStore()).resolve(repository(headCommit), change);

        assertThat(result.symbols())
                .filteredOn(symbol -> symbol.changeType() == RepositoryChange.ChangeType.DELETED)
                .extracting(
                        ChangedSymbolResolver.ChangedSymbol::name,
                        ChangedSymbolResolver.ChangedSymbol::filePath)
                .containsExactly(tuple("removeMe", "src/LegacyService.java"));
        assertThat(result.symbols().get(0).provenance())
                .anyMatch(provenance -> provenance.side() == ChangedSymbolResolver.Side.OLD);
    }

    @Test
    void pureRenamePreservesBothOldAndNewSymbolProvenance() throws Exception {
        git("mv", "src/LegacyService.java", "src/RenamedService.java");
        git("commit", "-m", "rename legacy service");
        String headCommit = git("rev-parse", "HEAD").trim();
        RepositoryChange change =
                changes.analyze(
                        GitChangeRequest.commitRange(repositoryRoot, baseCommit, headCommit));

        ChangedSymbolResolver.ResolutionResult result =
                resolver(new InMemoryCodeChunkStore()).resolve(repository(headCommit), change);

        assertThat(result.symbols())
                .filteredOn(symbol -> symbol.name().equals("removeMe"))
                .extracting(ChangedSymbolResolver.ChangedSymbol::filePath)
                .containsExactlyInAnyOrder("src/LegacyService.java", "src/RenamedService.java");
        assertThat(result.symbols())
                .filteredOn(symbol -> symbol.name().equals("removeMe"))
                .flatExtracting(ChangedSymbolResolver.ChangedSymbol::provenance)
                .extracting(ChangedSymbolResolver.Provenance::side)
                .contains(ChangedSymbolResolver.Side.OLD, ChangedSymbolResolver.Side.NEW);
    }

    @Test
    void usesVersionMatchedCodeGraphBeforeSourceAndRejectsMismatchedNodes() throws Exception {
        write(
                "src/AccountService.java",
                """
                public class AccountService {
                    public String findAccount(String id) {
                        return id.trim();
                    }
                }
                """);
        git("add", "-A");
        git("commit", "-m", "trim account id");
        String headCommit = git("rev-parse", "HEAD").trim();
        RepositoryChange change =
                changes.analyze(
                        GitChangeRequest.commitRange(repositoryRoot, baseCommit, headCommit));
        CodeRepository repository = repository(headCommit);
        ChangedSymbolResolver.CodeGraphSymbolLookup lookup =
                request ->
                        List.of(
                                new ChangedSymbolResolver.GraphSymbol(
                                        request.repositoryId(),
                                        request.snapshotId(),
                                        request.commitSha(),
                                        request.filePath(),
                                        "graph:findAccount",
                                        "findAccount",
                                        "METHOD",
                                        2,
                                        4),
                                new ChangedSymbolResolver.GraphSymbol(
                                        request.repositoryId(),
                                        request.snapshotId(),
                                        "0".repeat(40),
                                        request.filePath(),
                                        "imaginary",
                                        "imaginary",
                                        "METHOD",
                                        2,
                                        4));
        ChangedSymbolResolver resolver =
                new ChangedSymbolResolver(
                        extractor, new InMemoryCodeChunkStore(), gitClient, List.of(lookup));

        ChangedSymbolResolver.ResolutionResult result = resolver.resolve(repository, change);

        assertThat(result.symbols())
                .extracting(
                        ChangedSymbolResolver.ChangedSymbol::symbolId,
                        ChangedSymbolResolver.ChangedSymbol::resolution)
                .containsExactly(
                        tuple("graph:findAccount", ChangedSymbolResolver.Resolution.CODEGRAPH));
        assertThat(result.symbols()).noneMatch(symbol -> symbol.symbolId().equals("imaginary"));
        assertThat(result.unknowns())
                .extracting(ChangedSymbolResolver.ResolutionUnknown::code)
                .contains("CODEGRAPH_VERSION_MISMATCH");
    }

    @Test
    void fallsBackToSameCommitChunkAndThenToAnExplicitFileObject() throws Exception {
        write("plain.txt", "new factual line\n");
        write("unsupported.bin.txt", "another factual line\n");
        git("add", "-A");
        git("commit", "-m", "add plain files");
        String headCommit = git("rev-parse", "HEAD").trim();
        RepositoryChange change =
                changes.analyze(
                        GitChangeRequest.commitRange(repositoryRoot, baseCommit, headCommit));
        CodeRepository repository = repository(headCommit);
        InMemoryCodeChunkStore chunks = new InMemoryCodeChunkStore();
        CodeChunk chunk =
                CodeChunk.symbolChunk(
                        repository.id(),
                        repository.currentSnapshotId(),
                        headCommit,
                        "plain.txt",
                        "text",
                        RepositoryAssetType.CODE,
                        "indexedFact",
                        "SECTION",
                        1,
                        1,
                        "new factual line");
        chunks.replaceRepositoryChunks(repository.id(), List.of(chunk));

        ChangedSymbolResolver.ResolutionResult result =
                resolver(chunks).resolve(repository, change);

        assertThat(result.symbols())
                .filteredOn(symbol -> symbol.filePath().equals("plain.txt"))
                .extracting(
                        ChangedSymbolResolver.ChangedSymbol::name,
                        ChangedSymbolResolver.ChangedSymbol::resolution)
                .containsExactly(
                        tuple("indexedFact", ChangedSymbolResolver.Resolution.CHUNK_SYMBOL));
        assertThat(result.symbols())
                .filteredOn(symbol -> symbol.filePath().equals("unsupported.bin.txt"))
                .extracting(ChangedSymbolResolver.ChangedSymbol::resolution)
                .containsExactly(ChangedSymbolResolver.Resolution.FILE_LEVEL);
        assertThat(result.unknowns())
                .anyMatch(
                        unknown ->
                                unknown.filePath().equals("unsupported.bin.txt")
                                        && unknown.code().equals("NO_SUPPORTED_DECLARATION"));
    }

    @Test
    void largeSourceIsExplicitlyDowngradedWithoutParsingInventedSymbols() throws Exception {
        write("src/Large.java", "x".repeat(ChangedSymbolResolver.MAX_FILE_BYTES + 1));
        git("add", "-A");
        git("commit", "-m", "add large file");
        String headCommit = git("rev-parse", "HEAD").trim();
        RepositoryChange change =
                changes.analyze(
                        GitChangeRequest.commitRange(repositoryRoot, baseCommit, headCommit));

        ChangedSymbolResolver.ResolutionResult result =
                resolver(new InMemoryCodeChunkStore()).resolve(repository(headCommit), change);

        assertThat(result.symbols())
                .filteredOn(symbol -> symbol.filePath().equals("src/Large.java"))
                .extracting(ChangedSymbolResolver.ChangedSymbol::resolution)
                .containsExactly(ChangedSymbolResolver.Resolution.FILE_LEVEL);
        assertThat(result.unknowns())
                .anyMatch(unknown -> unknown.code().equals("FILE_SIZE_LIMIT_EXCEEDED"));
    }

    private ChangedSymbolResolver resolver(InMemoryCodeChunkStore chunks) {
        return new ChangedSymbolResolver(extractor, chunks, gitClient);
    }

    private CodeRepository repository(String commit) {
        Instant now = Instant.now();
        CodeRepositoryId repositoryId = CodeRepositoryId.newId();
        RepositorySnapshotId snapshotId = RepositorySnapshotId.newId();
        return new CodeRepository(
                repositoryId,
                "resolver-test",
                repositoryRoot,
                RepositorySourceType.LOCAL_GIT,
                "main",
                commit,
                null,
                false,
                snapshotId,
                repositoryRoot,
                repositoryRoot.resolve(".codegraph"),
                now,
                now,
                now,
                now);
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = repositoryRoot.resolve(relativePath);
        Files.createDirectories(target.getParent() == null ? repositoryRoot : target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repositoryRoot.toString());
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(new String(output, StandardCharsets.UTF_8));
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
