package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotStore;
import com.analyzercoder.infrastructure.persistence.mapper.RepositorySnapshotMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Primary
@Repository
public class PostgresRepositorySnapshotStore implements RepositorySnapshotStore {
    private final RepositorySnapshotMapper mapper;
    public PostgresRepositorySnapshotStore(RepositorySnapshotMapper mapper){this.mapper=mapper;}
    @Override public ManagedRepositorySnapshot save(ManagedRepositorySnapshot snapshot){mapper.insert(snapshot.id().value(),snapshot.repositoryId().value(),snapshot.sourceCommit(),snapshot.worktreeDigest(),snapshot.contentPath().toString(),snapshot.createdAt());return snapshot;}
    @Override public void deleteByRepositoryId(CodeRepositoryId repositoryId){mapper.deleteByRepositoryId(repositoryId.value());}
}