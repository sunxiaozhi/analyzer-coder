package com.analyzercoder.application.indexing;

import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.RepositoryScannerPort;
import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IndexJobProcessor {

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
        return indexJobStore.findNextQueued()
            .map(this::process)
            .orElse(false);
    }

    private boolean process(IndexJob indexJob) {
        IndexJob runningJob = indexJobStore.save(indexJob.start("scan_repository"));
        try {
            CodeRepository repository = repositoryStore.findById(runningJob.repositoryId())
                .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + runningJob.repositoryId().value()));

            List<CodeChunk> chunks = repositoryScannerPort.scan(repository).stream()
                .map(scannedFile -> CodeChunk.fileChunk(
                    repository.id(),
                    repository.currentCommit(),
                    scannedFile.relativePath(),
                    scannedFile.language(),
                    1,
                    scannedFile.lineCount(),
                    scannedFile.content()
                ))
                .toList();

            indexJobStore.save(runningJob.start("write_chunks"));
            codeChunkStore.replaceRepositoryChunks(repository.id(), chunks);
            indexJobStore.save(runningJob.succeed("indexed " + chunks.size() + " chunks"));
            return true;
        } catch (Exception exception) {
            indexJobStore.save(runningJob.fail("failed", exception.getMessage()));
            return false;
        }
    }
}
