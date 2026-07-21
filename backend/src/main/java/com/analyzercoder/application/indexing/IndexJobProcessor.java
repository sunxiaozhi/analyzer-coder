package com.analyzercoder.application.indexing;

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
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IndexJobProcessor {
    private static final int MAX_CHUNK_LINES = 200;
    private final IndexJobStore indexJobStore;
    private final CodeRepositoryStore repositoryStore;
    private final RepositoryScannerPort repositoryScannerPort;
    private final CodeChunkStore codeChunkStore;

    public IndexJobProcessor(
        IndexJobStore indexJobStore,
        CodeRepositoryStore repositoryStore,
        RepositoryScannerPort repositoryScannerPort,
        CodeChunkStore codeChunkStore
    ) {
        this.indexJobStore = indexJobStore;
        this.repositoryStore = repositoryStore;
        this.repositoryScannerPort = repositoryScannerPort;
        this.codeChunkStore = codeChunkStore;
    }

    public boolean processNextQueuedJob() {
        return indexJobStore.claimNextQueued().map(this::process).orElse(false);
    }

    private boolean process(IndexJob runningJob) {
        try {
            if (finishCancellation(runningJob.id())) return true;
            CodeRepository repository = repositoryStore.findById(runningJob.repositoryId())
                .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + runningJob.repositoryId().value()));
            if (repository.currentSnapshotId() == null) throw new IllegalStateException("Repository has no published snapshot");

            List<CodeChunk> chunks = repositoryScannerPort.scan(repository).stream()
                .flatMap(file -> splitIntoChunks(repository, file).stream()).toList();
            if (finishCancellation(runningJob.id())) return true;

            IndexJob writingJob = indexJobStore.findById(runningJob.id()).orElseThrow().start("write_chunks");
            indexJobStore.save(writingJob);
            codeChunkStore.replaceRepositoryChunks(repository.id(), chunks);
            IndexJob publishState = indexJobStore.findById(runningJob.id()).orElseThrow();
            indexJobStore.save(publishState.succeed("completed:" + chunks.size()));
            return true;
        } catch (Exception exception) {
            IndexJob latest = indexJobStore.findById(runningJob.id()).orElse(runningJob);
            indexJobStore.save(latest.fail("failed", safeMessage(exception)));
            return false;
        }
    }

    private boolean finishCancellation(IndexJobId indexJobId) {
        IndexJob current = indexJobStore.findById(indexJobId).orElseThrow();
        if (!current.isCancellationRequested()) return false;
        indexJobStore.save(current.cancel());
        return true;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private List<CodeChunk> splitIntoChunks(CodeRepository repository, ScannedRepositoryFile scannedFile) {
        String[] lines = scannedFile.content().split("\\R", -1);
        List<CodeChunk> chunks = new ArrayList<>();
        for (int start = 0; start < lines.length; start += MAX_CHUNK_LINES) {
            int end = Math.min(start + MAX_CHUNK_LINES, lines.length);
            String content = String.join("\n", java.util.Arrays.copyOfRange(lines, start, end));
            if (!content.isBlank()) {
                chunks.add(CodeChunk.fileChunk(
                    repository.id(), repository.currentSnapshotId(), repository.currentCommit(), scannedFile.relativePath(),
                    scannedFile.language(), start + 1, end, content
                ));
            }
        }
        return chunks;
    }
}
