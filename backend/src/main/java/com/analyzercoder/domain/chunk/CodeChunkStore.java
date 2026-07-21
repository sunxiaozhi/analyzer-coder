package com.analyzercoder.domain.chunk;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.Collection;
import java.util.List;

public interface CodeChunkStore {

    void replaceRepositoryChunks(CodeRepositoryId repositoryId, Collection<CodeChunk> chunks);

    List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId);

    List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId, int limit, int offset);

    List<CodeChunk> searchByRepositoryId(CodeRepositoryId repositoryId, String query, int limit, int offset);

    long countByRepositoryId(CodeRepositoryId repositoryId);

    long countSearchByRepositoryId(CodeRepositoryId repositoryId, String query);

    void deleteByRepositoryId(CodeRepositoryId repositoryId);
}
