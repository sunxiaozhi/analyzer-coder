package com.analyzercoder.application.chunk;

import com.analyzercoder.domain.chunk.CodeChunk;
import java.util.List;

public record CodeChunkQueryResult(
    long total,
    int limit,
    int offset,
    List<CodeChunk> chunks
) {
}
