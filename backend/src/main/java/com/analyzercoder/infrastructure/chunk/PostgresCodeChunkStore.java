package com.analyzercoder.infrastructure.chunk;

import com.analyzercoder.domain.chunk.ChunkType;
import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkId;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import com.analyzercoder.domain.repository.RepositorySnapshotId;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Primary
@Repository
public class PostgresCodeChunkStore implements CodeChunkStore {

    private static final String SELECT_COLUMNS = """
        SELECT id, repo_id, snapshot_id, commit_sha, file_path, symbol_id, symbol_name, symbol_kind,
               language, chunk_type, start_line, end_line, content, content_hash, created_at
        FROM code_chunks
        """;
    private static final RowMapper<CodeChunk> ROW_MAPPER = PostgresCodeChunkStore::mapRow;
    private final JdbcTemplate jdbcTemplate;

    public PostgresCodeChunkStore(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Override
    @Transactional
    public void replaceRepositoryChunks(CodeRepositoryId repositoryId, Collection<CodeChunk> chunks) {
        jdbcTemplate.update("DELETE FROM code_chunks WHERE repo_id = ?", repositoryId.value());
        if (chunks.isEmpty()) return;
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO code_chunks (
                id, repo_id, snapshot_id, commit_sha, file_path, symbol_id, symbol_name, symbol_kind,
                language, chunk_type, start_line, end_line, content, content_hash, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            new ArrayList<>(chunks), 250, PostgresCodeChunkStore::setParameters
        );
    }

    @Override
    public List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId) {
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE repo_id = ? ORDER BY file_path, start_line, id", ROW_MAPPER, repositoryId.value());
    }

    @Override
    public List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId, int limit, int offset) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + " WHERE repo_id = ? ORDER BY file_path, start_line, id LIMIT ? OFFSET ?",
            ROW_MAPPER, repositoryId.value(), limit, offset
        );
    }

    @Override
    public List<CodeChunk> searchByRepositoryId(CodeRepositoryId repositoryId, String query, int limit, int offset) {
        return jdbcTemplate.query(
            SELECT_COLUMNS + """
                WHERE repo_id = ? AND (
                    POSITION(LOWER(?) IN LOWER(file_path)) > 0
                    OR POSITION(LOWER(?) IN LOWER(COALESCE(symbol_name, ''))) > 0
                    OR POSITION(LOWER(?) IN LOWER(COALESCE(symbol_kind, ''))) > 0
                    OR POSITION(LOWER(?) IN LOWER(COALESCE(language, ''))) > 0
                    OR POSITION(LOWER(?) IN LOWER(content)) > 0
                )
                ORDER BY
                    CASE WHEN POSITION(LOWER(?) IN LOWER(file_path)) > 0 THEN 4 ELSE 0 END
                    + CASE WHEN POSITION(LOWER(?) IN LOWER(COALESCE(symbol_name, ''))) > 0 THEN 3 ELSE 0 END
                    + CASE WHEN POSITION(LOWER(?) IN LOWER(COALESCE(symbol_kind, ''))) > 0 THEN 2 ELSE 0 END
                    + CASE WHEN POSITION(LOWER(?) IN LOWER(COALESCE(language, ''))) > 0 THEN 2 ELSE 0 END
                    + CASE WHEN POSITION(LOWER(?) IN LOWER(content)) > 0 THEN 1 ELSE 0 END DESC,
                    file_path, start_line, id LIMIT ? OFFSET ?
                """,
            ROW_MAPPER, repositoryId.value(), query, query, query, query, query,
            query, query, query, query, query, limit, offset
        );
    }

    @Override
    public long countByRepositoryId(CodeRepositoryId repositoryId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM code_chunks WHERE repo_id = ?", Long.class, repositoryId.value());
        return count == null ? 0 : count;
    }

    @Override
    public long countSearchByRepositoryId(CodeRepositoryId repositoryId, String query) {
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM code_chunks WHERE repo_id = ? AND (
                POSITION(LOWER(?) IN LOWER(file_path)) > 0
                OR POSITION(LOWER(?) IN LOWER(COALESCE(symbol_name, ''))) > 0
                OR POSITION(LOWER(?) IN LOWER(COALESCE(symbol_kind, ''))) > 0
                OR POSITION(LOWER(?) IN LOWER(COALESCE(language, ''))) > 0
                OR POSITION(LOWER(?) IN LOWER(content)) > 0
            )
            """,
            Long.class, repositoryId.value(), query, query, query, query, query
        );
        return count == null ? 0 : count;
    }

    @Override
    public void deleteByRepositoryId(CodeRepositoryId repositoryId) {
        jdbcTemplate.update("DELETE FROM code_chunks WHERE repo_id = ?", repositoryId.value());
    }

    private static void setParameters(PreparedStatement statement, CodeChunk chunk) throws SQLException {
        statement.setObject(1, chunk.id().value());
        statement.setObject(2, chunk.repositoryId().value());
        statement.setObject(3, chunk.snapshotId().value());
        statement.setString(4, chunk.commitSha());
        statement.setString(5, chunk.filePath());
        statement.setString(6, chunk.symbolId());
        statement.setString(7, chunk.symbolName());
        statement.setString(8, chunk.symbolKind());
        statement.setString(9, chunk.language());
        statement.setString(10, chunk.chunkType().name());
        statement.setObject(11, chunk.startLine());
        statement.setObject(12, chunk.endLine());
        statement.setString(13, chunk.content());
        statement.setString(14, chunk.contentHash());
        statement.setTimestamp(15, Timestamp.from(chunk.createdAt()));
    }

    private static CodeChunk mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID snapshotId = resultSet.getObject("snapshot_id", UUID.class);
        if (snapshotId == null) throw new SQLException("Code chunk is missing snapshot_id");
        return new CodeChunk(
            CodeChunkId.of(resultSet.getObject("id", UUID.class)),
            CodeRepositoryId.of(resultSet.getObject("repo_id", UUID.class)), RepositorySnapshotId.of(snapshotId),
            resultSet.getString("commit_sha"), resultSet.getString("file_path"), resultSet.getString("symbol_id"),
            resultSet.getString("symbol_name"), resultSet.getString("symbol_kind"), resultSet.getString("language"),
            ChunkType.valueOf(resultSet.getString("chunk_type")), resultSet.getObject("start_line", Integer.class),
            resultSet.getObject("end_line", Integer.class), resultSet.getString("content"),
            resultSet.getString("content_hash"), resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
