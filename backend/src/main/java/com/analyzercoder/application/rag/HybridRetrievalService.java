package com.analyzercoder.application.rag;

import com.analyzercoder.domain.codegraph.CodeGraphPort;
import com.analyzercoder.domain.rag.VectorSearchPort;
import org.springframework.stereotype.Service;

@Service
public class HybridRetrievalService {

    private final VectorSearchPort vectorSearchPort;
    private final CodeGraphPort codeGraphPort;

    public HybridRetrievalService(VectorSearchPort vectorSearchPort, CodeGraphPort codeGraphPort) {
        this.vectorSearchPort = vectorSearchPort;
        this.codeGraphPort = codeGraphPort;
    }

    public VectorSearchPort vectorSearchPort() {
        return vectorSearchPort;
    }

    public CodeGraphPort codeGraphPort() {
        return codeGraphPort;
    }
}

