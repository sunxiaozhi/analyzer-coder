package com.analyzercoder.application.chunk;

import com.analyzercoder.domain.chunk.CodeChunk;
import java.util.List;

/** 封装代码片段用例的返回数据，避免接口层依赖内部领域对象。 */
public record CodeChunkQueryResult(long total, int limit, int offset, List<CodeChunk> chunks) {}
