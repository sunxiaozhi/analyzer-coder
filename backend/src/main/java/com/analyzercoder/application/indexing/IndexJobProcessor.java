package com.analyzercoder.application.indexing;

import com.analyzercoder.application.intelligence.IntelligenceService;
import com.analyzercoder.application.intelligence.MarkdownKnowledgeSourceService;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.indexing.ScannedRepositoryFile;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 执行单个索引任务的状态机，串联仓库扫描、代码分块、向量写入及失败回收。 */
@Service
public class IndexJobProcessor {
    private static final int MAX_CHUNK_LINES = 120;
    private static final int CHUNK_OVERLAP_LINES = 20;
    private static final Pattern DECLARATION =
            Pattern.compile(
                    "(?m)^\\s*(?:(?:public|protected|private|static|final|abstract|async|export|default)\\s+)*"
                            + "(class|interface|record|enum|function|def|func|type)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern CALLABLE =
            Pattern.compile(
                    "(?m)^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|async|export)\\s+)+"
                            + "(?:[\\w<>\\[\\],.?]+\\s+)?([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");

    private final IndexJobStore indexJobStore;
    private final CodeRepositoryStore repositoryStore;
    private final RepositoryScannerPort repositoryScannerPort;
    private final CodeChunkStore codeChunkStore;
    private final IntelligenceService intelligenceService;
    private final MarkdownKnowledgeSourceService markdownKnowledgeSourceService;
    private final GitDiffService gitDiffService;

    @Autowired
    public IndexJobProcessor(
            IndexJobStore indexJobStore,
            CodeRepositoryStore repositoryStore,
            RepositoryScannerPort repositoryScannerPort,
            CodeChunkStore codeChunkStore,
            IntelligenceService intelligenceService,
            MarkdownKnowledgeSourceService markdownKnowledgeSourceService,
            GitDiffService gitDiffService) {
        this.indexJobStore = indexJobStore;
        this.repositoryStore = repositoryStore;
        this.repositoryScannerPort = repositoryScannerPort;
        this.codeChunkStore = codeChunkStore;
        this.intelligenceService = intelligenceService;
        this.markdownKnowledgeSourceService = markdownKnowledgeSourceService;
        this.gitDiffService = gitDiffService;
    }

    public IndexJobProcessor(
            IndexJobStore indexJobStore,
            CodeRepositoryStore repositoryStore,
            RepositoryScannerPort repositoryScannerPort,
            CodeChunkStore codeChunkStore) {
        this(
                indexJobStore,
                repositoryStore,
                repositoryScannerPort,
                codeChunkStore,
                null,
                null,
                new GitDiffService());
    }

    public boolean processNextQueuedJob() {
        return indexJobStore.claimNextQueued().map(this::process).orElse(false);
    }

    private boolean process(IndexJob runningJob) {
        try {
            if (finishCancellation(runningJob.id())) {
                return true;
            }
            CodeRepository repository =
                    repositoryStore
                            .findById(runningJob.repositoryId())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Repository not found: "
                                                            + runningJob.repositoryId().value()));
            if (repository.currentSnapshotId() == null) {
                throw new IllegalStateException("仓库尚未发布可用的代码版本");
            }

            String indexedCommit = codeChunkStore.latestIndexedCommit(repository.id());
            boolean incremental =
                    runningJob.type() == com.analyzercoder.domain.indexing.IndexJobType.INCREMENTAL
                            && indexedCommit != null
                            && !indexedCommit.isBlank()
                            && !repository.worktreeDirty();
            Set<String> changedPaths =
                    incremental
                            ? gitDiffService.changedPaths(repository, indexedCommit)
                            : Set.of();
            List<ScannedRepositoryFile> allFiles = repositoryScannerPort.scan(repository);
            List<CodeChunk> chunks =
                    allFiles.stream()
                            .filter(
                                    file ->
                                            !incremental
                                                    || changedPaths.contains(
                                                            file.relativePath().replace('\\', '/')))
                            .flatMap(file -> splitIntoChunks(repository, file).stream())
                            .toList();
            if (finishCancellation(runningJob.id())) {
                return true;
            }

            IndexJob writingJob =
                    indexJobStore.findById(runningJob.id()).orElseThrow().start("write_chunks");
            indexJobStore.save(writingJob);
            if (incremental) {
                codeChunkStore.replaceRepositoryPaths(
                        repository.id(),
                        changedPaths,
                        chunks,
                        repository.currentSnapshotId(),
                        repository.currentCommit());
            } else {
                codeChunkStore.replaceRepositoryChunks(repository.id(), chunks);
            }
            if (markdownKnowledgeSourceService != null) {
                markdownKnowledgeSourceService.synchronize(
                        repository, allFiles, incremental, changedPaths);
            }

            boolean vectorsReady = true;
            if (intelligenceService != null) {
                IndexJob vectorJob =
                        indexJobStore
                                .findById(runningJob.id())
                                .orElseThrow()
                                .start("build_embeddings");
                indexJobStore.save(vectorJob);
                vectorsReady =
                        intelligenceService.prepareRepositoryEmbeddings(repository.id().value());
            }

            IndexJob publishState = indexJobStore.findById(runningJob.id()).orElseThrow();
            String completion =
                    (incremental ? "incremental" : "full")
                            + ":completed:"
                            + chunks.size()
                            + (vectorsReady ? ":vectors-ready" : ":vectors-degraded");
            indexJobStore.save(publishState.succeed(completion));
            return true;
        } catch (Exception exception) {
            IndexJob latest = indexJobStore.findById(runningJob.id()).orElse(runningJob);
            indexJobStore.save(latest.fail("failed", safeMessage(exception)));
            return false;
        }
    }

    private boolean finishCancellation(IndexJobId indexJobId) {
        IndexJob current = indexJobStore.findById(indexJobId).orElseThrow();
        if (!current.isCancellationRequested()) {
            return false;
        }
        indexJobStore.save(current.cancel());
        return true;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private List<CodeChunk> splitIntoChunks(
            CodeRepository repository, ScannedRepositoryFile scannedFile) {
        String[] lines = scannedFile.content().split("\\R", -1);
        List<CodeChunk> chunks = new ArrayList<>();
        int step = MAX_CHUNK_LINES - CHUNK_OVERLAP_LINES;
        for (int start = 0; start < lines.length; start += step) {
            int end = Math.min(start + MAX_CHUNK_LINES, lines.length);
            String content = String.join("\n", Arrays.copyOfRange(lines, start, end));
            if (!content.isBlank()) {
                Symbol symbol =
                        inferSymbol(content, scannedFile.relativePath(), scannedFile.language());
                CodeChunk chunk =
                        symbol == null
                                ? CodeChunk.fileChunk(
                                        repository.id(),
                                        repository.currentSnapshotId(),
                                        repository.currentCommit(),
                                        scannedFile.relativePath(),
                                        scannedFile.language(),
                                        scannedFile.assetType(),
                                        start + 1,
                                        end,
                                        content)
                                : CodeChunk.symbolChunk(
                                        repository.id(),
                                        repository.currentSnapshotId(),
                                        repository.currentCommit(),
                                        scannedFile.relativePath(),
                                        scannedFile.language(),
                                        scannedFile.assetType(),
                                        symbol.name(),
                                        symbol.kind(),
                                        start + 1,
                                        end,
                                        content);
                chunks.add(chunk);
            }
            if (end == lines.length) {
                break;
            }
        }
        return chunks;
    }

    private static Symbol inferSymbol(String content, String filePath, String language) {
        if ("markdown".equals(language)) {
            Matcher heading = MARKDOWN_HEADING.matcher(content);
            if (heading.find()) {
                return new Symbol(heading.group(1).trim(), "DOC_SECTION");
            }
        }
        Matcher declaration = DECLARATION.matcher(content);
        if (declaration.find()) {
            return new Symbol(declaration.group(2), declaration.group(1).toUpperCase());
        }
        Matcher callable = CALLABLE.matcher(content);
        if (callable.find()) {
            return new Symbol(callable.group(1), "CALLABLE");
        }
        String normalized = filePath.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        int extension = fileName.lastIndexOf('.');
        return new Symbol(extension > 0 ? fileName.substring(0, extension) : fileName, "FILE");
    }

    private record Symbol(String name, String kind) {}
}
