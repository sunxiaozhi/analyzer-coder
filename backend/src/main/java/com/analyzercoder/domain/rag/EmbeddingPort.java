package com.analyzercoder.domain.rag;

import java.util.List;

public interface EmbeddingPort {

    EmbeddingVector embed(String text);

    List<EmbeddingVector> embedAll(List<String> texts);
}

