package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryRepositorySnapshotStore implements RepositorySnapshotStore {
    private final Map<UUID, ManagedRepositorySnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public ManagedRepositorySnapshot save(ManagedRepositorySnapshot snapshot) {
        snapshots.put(snapshot.id().value(), snapshot);
        return snapshot;
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId repositoryId) {
        snapshots.values().removeIf(snapshot -> snapshot.repositoryId().equals(repositoryId));
    }
}
