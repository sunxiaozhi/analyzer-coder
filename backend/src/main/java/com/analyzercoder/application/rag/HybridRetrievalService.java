package com.analyzercoder.application.rag;

import com.analyzercoder.domain.codegraph.CodeGraphPort;
import com.analyzercoder.domain.rag.VectorSearchPort;
import org.springframework.stereotype.Service;

/** 融合向量召回、结构化过滤和代码图谱扩展，为问答流程提供可追溯证据。 */
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
