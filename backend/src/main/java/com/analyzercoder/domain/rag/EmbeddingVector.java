package com.analyzercoder.domain.rag;

import java.util.List;

public record EmbeddingVector(
    String model,
    List<Float> values
) {
}

