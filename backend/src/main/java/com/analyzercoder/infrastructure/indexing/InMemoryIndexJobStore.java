package com.analyzercoder.infrastructure.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryIndexJobStore implements IndexJobStore {

    private final Map<UUID, IndexJob> indexJobs = new ConcurrentHashMap<>();

    @Override
    public IndexJob save(IndexJob indexJob) {
        indexJobs.put(indexJob.id().value(), indexJob);
        return indexJob;
    }

    @Override
    public Optional<IndexJob> findById(IndexJobId indexJobId) {
        return Optional.ofNullable(indexJobs.get(indexJobId.value()));
    }

    @Override
    public Optional<IndexJob> findLatestByRepositoryId(CodeRepositoryId repositoryId) {
        return indexJobs.values().stream()
            .filter(indexJob -> indexJob.repositoryId().equals(repositoryId))
            .max(Comparator.comparing(IndexJob::createdAt));
    }

    @Override
    public List<IndexJob> findByRepositoryId(CodeRepositoryId repositoryId) {
        return indexJobs.values().stream()
            .filter(indexJob -> indexJob.repositoryId().equals(repositoryId))
            .sorted(Comparator.comparing(IndexJob::createdAt).reversed())
            .toList();
    }

    @Override
    public Optional<IndexJob> findNextQueued() {
        return indexJobs.values().stream()
            .filter(indexJob -> indexJob.status() == IndexJobStatus.QUEUED)
            .min(Comparator.comparing(IndexJob::createdAt));
    }
}
