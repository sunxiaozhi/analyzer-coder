package com.analyzercoder.infrastructure.indexing;

import com.analyzercoder.domain.indexing.*;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.infrastructure.persistence.mapper.IndexJobMapper;
import com.analyzercoder.infrastructure.persistence.model.IndexJobRow;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Primary @Repository
public class PostgresIndexJobStore implements IndexJobStore {
    private final IndexJobMapper mapper;
    public PostgresIndexJobStore(IndexJobMapper mapper){this.mapper=mapper;}
    @Override public IndexJob save(IndexJob job){mapper.upsert(row(job));return job;}
    @Override public Optional<IndexJob> findById(IndexJobId id){return Optional.ofNullable(domain(mapper.findById(id.value())));}
    @Override public Optional<IndexJob> findLatestByRepositoryId(CodeRepositoryId id){return Optional.ofNullable(domain(mapper.findLatest(id.value())));}
    @Override public List<IndexJob> findByRepositoryId(CodeRepositoryId id){return mapper.findByRepositoryId(id.value()).stream().map(PostgresIndexJobStore::domain).toList();}
    @Override public List<IndexJob> findAll(){return mapper.findAll().stream().map(PostgresIndexJobStore::domain).toList();}
    @Override public boolean hasActiveJob(CodeRepositoryId id){return mapper.countActive(id.value())>0;}
    @Override public Optional<IndexJob> findNextQueued(){return Optional.ofNullable(domain(mapper.findNextQueued()));}
    @Override @Transactional public Optional<IndexJob> claimNextQueued(){return Optional.ofNullable(domain(mapper.claimNextQueued()));}
    @Override public void deleteByRepositoryId(CodeRepositoryId id){mapper.deleteByRepositoryId(id.value());}
    private static IndexJobRow row(IndexJob job){return new IndexJobRow(job.id().value(),job.repositoryId().value(),job.type().name(),job.status().name(),job.currentStep(),job.errorMessage(),job.startedAt(),job.finishedAt(),job.createdAt());}
    private static IndexJob domain(IndexJobRow row){return row==null?null:new IndexJob(IndexJobId.of(row.id()),CodeRepositoryId.of(row.repositoryId()),IndexJobType.valueOf(row.jobType()),IndexJobStatus.valueOf(row.status()),row.currentStep(),row.errorMessage(),row.startedAt(),row.finishedAt(),row.createdAt());}
}