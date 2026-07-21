package com.analyzercoder.infrastructure.indexing;

import com.analyzercoder.domain.indexing.IndexJob;
import com.analyzercoder.domain.indexing.IndexJobId;
import com.analyzercoder.domain.indexing.IndexJobStatus;
import com.analyzercoder.domain.indexing.IndexJobStore;
import com.analyzercoder.domain.indexing.IndexJobType;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Primary
@Repository
public class PostgresIndexJobStore implements IndexJobStore {

    private static final RowMapper<IndexJob> ROW_MAPPER = PostgresIndexJobStore::mapRow;
    private final JdbcTemplate jdbcTemplate;

    public PostgresIndexJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public IndexJob save(IndexJob indexJob) {
        jdbcTemplate.update(
            """
            INSERT INTO index_jobs (
                id, repo_id, job_type, status, current_step, error_message,
                started_at, finished_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                current_step = EXCLUDED.current_step,
                error_message = EXCLUDED.error_message,
                started_at = EXCLUDED.started_at,
                finished_at = EXCLUDED.finished_at
            """,
            indexJob.id().value(),
            indexJob.repositoryId().value(),
            indexJob.type().name(),
            indexJob.status().name(),
            indexJob.currentStep(),
            indexJob.errorMessage(),
            timestamp(indexJob.startedAt()),
            timestamp(indexJob.finishedAt()),
            Timestamp.from(indexJob.createdAt())
        );
        return indexJob;
    }

    @Override
    public Optional<IndexJob> findById(IndexJobId indexJobId) {
        return queryOne("SELECT * FROM index_jobs WHERE id = ?", indexJobId.value());
    }

    @Override
    public Optional<IndexJob> findLatestByRepositoryId(CodeRepositoryId repositoryId) {
        return queryOne(
            "SELECT * FROM index_jobs WHERE repo_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            repositoryId.value()
        );
    }

    @Override
    public List<IndexJob> findByRepositoryId(CodeRepositoryId repositoryId) {
        return jdbcTemplate.query(
            "SELECT * FROM index_jobs WHERE repo_id = ? ORDER BY created_at DESC, id DESC",
            ROW_MAPPER,
            repositoryId.value()
        );
    }

    @Override
    public List<IndexJob> findAll() {
        return jdbcTemplate.query("SELECT * FROM index_jobs ORDER BY created_at DESC, id DESC", ROW_MAPPER);
    }

    @Override
    public boolean hasActiveJob(CodeRepositoryId repositoryId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM index_jobs WHERE repo_id = ? "
                + "AND status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED')",
            Integer.class,
            repositoryId.value()
        );
        return count != null && count > 0;
    }

    @Override
    public Optional<IndexJob> findNextQueued() {
        return jdbcTemplate.query(
            "SELECT * FROM index_jobs WHERE status = 'QUEUED' ORDER BY created_at, id LIMIT 1",
            ROW_MAPPER
        ).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<IndexJob> claimNextQueued() {
        return jdbcTemplate.query(
            """
            WITH candidate AS (
                SELECT id FROM index_jobs
                WHERE status = 'QUEUED'
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE index_jobs AS job
            SET status = 'RUNNING',
                current_step = 'scan_repository',
                started_at = CURRENT_TIMESTAMP
            FROM candidate
            WHERE job.id = candidate.id
            RETURNING job.*
            """,
            ROW_MAPPER
        ).stream().findFirst();
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId repositoryId) {
        jdbcTemplate.update("DELETE FROM index_jobs WHERE repo_id = ?", repositoryId.value());
    }

    private Optional<IndexJob> queryOne(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, ROW_MAPPER, arguments).stream().findFirst();
    }

    private static IndexJob mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IndexJob(
            IndexJobId.of(resultSet.getObject("id", UUID.class)),
            CodeRepositoryId.of(resultSet.getObject("repo_id", UUID.class)),
            IndexJobType.valueOf(resultSet.getString("job_type")),
            IndexJobStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("current_step"),
            resultSet.getString("error_message"),
            instant(resultSet.getTimestamp("started_at")),
            instant(resultSet.getTimestamp("finished_at")),
            resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
