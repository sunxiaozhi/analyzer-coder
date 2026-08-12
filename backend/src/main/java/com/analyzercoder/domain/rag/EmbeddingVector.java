package com.analyzercoder.domain.rag;

import java.util.List;

/** 描述向量嵌入的领域数据及其不变量，不依赖接口层或基础设施实现。 */
public record EmbeddingVector(String model, List<Float> values) {}
