package com.analyzercoder.infrastructure.chunk;

import com.analyzercoder.domain.chunk.CodeChunk;
import com.analyzercoder.domain.chunk.CodeChunkStore;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryCodeChunkStore implements CodeChunkStore {

    private final Map<UUID, List<CodeChunk>> chunksByRepository = new ConcurrentHashMap<>();

    @Override
    public void replaceRepositoryChunks(CodeRepositoryId repositoryId, Collection<CodeChunk> chunks) {
        List<CodeChunk> sortedChunks = chunks.stream()
            .sorted(Comparator.comparing(CodeChunk::filePath))
            .toList();
        chunksByRepository.put(repositoryId.value(), new ArrayList<>(sortedChunks));
    }

    @Override
    public List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId) {
        return chunksByRepository.getOrDefault(repositoryId.value(), List.of());
    }

    @Override
    public List<CodeChunk> findByRepositoryId(CodeRepositoryId repositoryId, int limit, int offset) {
        return findByRepositoryId(repositoryId).stream()
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public List<CodeChunk> searchByRepositoryId(CodeRepositoryId repositoryId, String query, int limit, int offset) {
        String normalizedQuery = normalize(query);
        return findByRepositoryId(repositoryId).stream()
            .filter(chunk -> matches(chunk, normalizedQuery))
            .sorted(Comparator
                .comparingInt((CodeChunk chunk) -> score(chunk, normalizedQuery)).reversed()
                .thenComparing(CodeChunk::filePath))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countByRepositoryId(CodeRepositoryId repositoryId) {
        return findByRepositoryId(repositoryId).size();
    }

    @Override
    public long countSearchByRepositoryId(CodeRepositoryId repositoryId, String query) {
        String normalizedQuery = normalize(query);
        return findByRepositoryId(repositoryId).stream()
            .filter(chunk -> matches(chunk, normalizedQuery))
            .count();
    }

    private boolean matches(CodeChunk chunk, String normalizedQuery) {
        return contains(chunk.filePath(), normalizedQuery)
            || contains(chunk.symbolName(), normalizedQuery)
            || contains(chunk.symbolKind(), normalizedQuery)
            || contains(chunk.language(), normalizedQuery)
            || contains(chunk.content(), normalizedQuery);
    }

    private int score(CodeChunk chunk, String normalizedQuery) {
        int score = 0;
        if (contains(chunk.filePath(), normalizedQuery)) {
            score += 4;
        }
        if (contains(chunk.symbolName(), normalizedQuery)) {
            score += 3;
        }
        if (contains(chunk.symbolKind(), normalizedQuery) || contains(chunk.language(), normalizedQuery)) {
            score += 2;
        }
        if (contains(chunk.content(), normalizedQuery)) {
            score += 1;
        }
        return score;
    }

    private boolean contains(String value, String normalizedQuery) {
        return value != null && normalize(value).contains(normalizedQuery);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
