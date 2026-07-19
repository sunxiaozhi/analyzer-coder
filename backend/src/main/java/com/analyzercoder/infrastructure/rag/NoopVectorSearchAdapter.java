package com.analyzercoder.infrastructure.rag;

import com.analyzercoder.domain.rag.VectorSearchHit;
import com.analyzercoder.domain.rag.VectorSearchPort;
import com.analyzercoder.domain.repository.CodeRepositoryId;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NoopVectorSearchAdapter implements VectorSearchPort {

    @Override
    public List<VectorSearchHit> search(CodeRepositoryId repositoryId, String query, int limit) {
        return List.of();
    }
}

