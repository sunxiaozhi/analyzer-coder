-- A pgvector value does not by itself imply semantic understanding. Persist the
-- generation capability so LOCAL_HASH can never be presented as an embedding model.

ALTER TABLE chunk_embeddings ADD COLUMN retrieval_capability VARCHAR(32);
ALTER TABLE knowledge_card_embeddings ADD COLUMN retrieval_capability VARCHAR(32);

UPDATE chunk_embeddings e
SET retrieval_capability = CASE
    WHEN e.model='local-hash-64' OR EXISTS (
        SELECT 1 FROM vector_model_configs vm
        WHERE vm.model=e.model AND vm.provider_type='LOCAL_HASH'
    ) THEN 'CHARACTER_HASH'
    ELSE 'SEMANTIC_EMBEDDING'
END;

UPDATE knowledge_card_embeddings e
SET retrieval_capability = CASE
    WHEN e.model='local-hash-64' OR EXISTS (
        SELECT 1 FROM vector_model_configs vm
        WHERE vm.model=e.model AND vm.provider_type='LOCAL_HASH'
    ) THEN 'CHARACTER_HASH'
    ELSE 'SEMANTIC_EMBEDDING'
END;

ALTER TABLE chunk_embeddings ALTER COLUMN retrieval_capability SET NOT NULL;
ALTER TABLE knowledge_card_embeddings ALTER COLUMN retrieval_capability SET NOT NULL;

ALTER TABLE chunk_embeddings ADD CONSTRAINT chk_chunk_embeddings_capability
    CHECK (retrieval_capability IN ('CHARACTER_HASH','SEMANTIC_EMBEDDING'));
ALTER TABLE knowledge_card_embeddings ADD CONSTRAINT chk_knowledge_embeddings_capability
    CHECK (retrieval_capability IN ('CHARACTER_HASH','SEMANTIC_EMBEDDING'));

COMMENT ON COLUMN chunk_embeddings.retrieval_capability IS
    'CHARACTER_HASH 为字符哈希相似度；SEMANTIC_EMBEDDING 才表示外部模型语义向量';
COMMENT ON COLUMN knowledge_card_embeddings.retrieval_capability IS
    'CHARACTER_HASH 为字符哈希相似度；SEMANTIC_EMBEDDING 才表示外部模型语义向量';
