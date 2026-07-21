package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.ManagedRepositorySnapshot;
import com.analyzercoder.domain.repository.RepositorySnapshotStore;
import java.sql.Timestamp;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Primary
@Repository
public class PostgresRepositorySnapshotStore implements RepositorySnapshotStore {
    private final JdbcTemplate jdbcTemplate;

    public PostgresRepositorySnapshotStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ManagedRepositorySnapshot save(ManagedRepositorySnapshot snapshot) {
        jdbcTemplate.update(
            """
            INSERT INTO repository_snapshots (
                id, repo_id, source_commit, worktree_digest, storage_path, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """,
            snapshot.id().value(), snapshot.repositoryId().value(), snapshot.sourceCommit(),
            snapshot.worktreeDigest(), snapshot.contentPath().toString(), Timestamp.from(snapshot.createdAt())
        );
        return snapshot;
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId repositoryId) {
        jdbcTemplate.update("DELETE FROM repository_snapshots WHERE repo_id = ?", repositoryId.value());
    }
}
