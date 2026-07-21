package com.analyzercoder.infrastructure.repository;

import com.analyzercoder.domain.repository.CodeRepository;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.CodeRepositoryStore;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import com.analyzercoder.domain.repository.RepositorySourceType;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Primary
@Repository
public class PostgresCodeRepositoryStore implements CodeRepositoryStore {

    private static final RowMapper<CodeRepository> ROW_MAPPER = PostgresCodeRepositoryStore::mapRow;
    private final JdbcTemplate jdbcTemplate;

    public PostgresCodeRepositoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public CodeRepository save(CodeRepository repository) {
        jdbcTemplate.update(
            """
            INSERT INTO repositories (
                id, name, path, source_type, default_branch, current_commit, worktree_digest,
                worktree_dirty, current_snapshot_id, current_snapshot_path, codegraph_path,
                snapshot_created_at, last_scanned_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name, path = EXCLUDED.path, source_type = EXCLUDED.source_type,
                default_branch = EXCLUDED.default_branch, current_commit = EXCLUDED.current_commit,
                worktree_digest = EXCLUDED.worktree_digest, worktree_dirty = EXCLUDED.worktree_dirty,
                current_snapshot_id = EXCLUDED.current_snapshot_id,
                current_snapshot_path = EXCLUDED.current_snapshot_path,
                codegraph_path = EXCLUDED.codegraph_path,
                snapshot_created_at = EXCLUDED.snapshot_created_at,
                last_scanned_at = EXCLUDED.last_scanned_at, updated_at = EXCLUDED.updated_at
            """,
            repository.id().value(), repository.name(), repository.path().toString(), repository.sourceType().name(),
            repository.defaultBranch(), repository.currentCommit(), repository.worktreeDigest(), repository.worktreeDirty(),
            uuid(repository.currentSnapshotId()), path(repository.currentSnapshotPath()), repository.codeGraphPath().toString(),
            timestamp(repository.snapshotCreatedAt()), timestamp(repository.lastScannedAt()),
            Timestamp.from(repository.createdAt()), Timestamp.from(repository.updatedAt())
        );
        return repository;
    }

    @Override
    public Optional<CodeRepository> findById(CodeRepositoryId repositoryId) {
        return jdbcTemplate.query("SELECT * FROM repositories WHERE id = ?", ROW_MAPPER, repositoryId.value())
            .stream().findFirst();
    }

    @Override
    public List<CodeRepository> findAll() {
        return jdbcTemplate.query("SELECT * FROM repositories ORDER BY created_at, id", ROW_MAPPER);
    }

    @Override
    public boolean existsByNormalizedName(String name) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM repositories WHERE LOWER(BTRIM(name)) = LOWER(BTRIM(?))", Integer.class, name
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByPath(Path path) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM repositories WHERE path = ?", Integer.class, path.toString());
        return count != null && count > 0;
    }

    @Override
    public void delete(CodeRepositoryId repositoryId) {
        jdbcTemplate.update("DELETE FROM repositories WHERE id = ?", repositoryId.value());
    }

    private static CodeRepository mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID snapshotId = resultSet.getObject("current_snapshot_id", UUID.class);
        String snapshotPath = resultSet.getString("current_snapshot_path");
        return new CodeRepository(
            CodeRepositoryId.of(resultSet.getObject("id", UUID.class)), resultSet.getString("name"),
            Path.of(resultSet.getString("path")), RepositorySourceType.valueOf(resultSet.getString("source_type")),
            resultSet.getString("default_branch"), resultSet.getString("current_commit"),
            resultSet.getString("worktree_digest"), resultSet.getBoolean("worktree_dirty"),
            snapshotId == null ? null : RepositorySnapshotId.of(snapshotId),
            snapshotPath == null ? null : Path.of(snapshotPath), Path.of(resultSet.getString("codegraph_path")),
            instant(resultSet.getTimestamp("snapshot_created_at")), instant(resultSet.getTimestamp("last_scanned_at")),
            resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static UUID uuid(RepositorySnapshotId value) { return value == null ? null : value.value(); }
    private static String path(Path value) { return value == null ? null : value.toString(); }
    private static Timestamp timestamp(java.time.Instant value) { return value == null ? null : Timestamp.from(value); }
    private static java.time.Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
