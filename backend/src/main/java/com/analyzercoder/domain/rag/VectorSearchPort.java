package com.analyzercoder.domain.rag;

import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;

public interface VectorSearchPort {

    List<VectorSearchHit> search(CodeRepositoryId repositoryId, String query, int limit);
}

