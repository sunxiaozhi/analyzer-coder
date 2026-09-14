-- Reuse is repository-local and requires identical content and vector configuration.
CREATE INDEX idx_chunk_embeddings_reuse
    ON chunk_embeddings(repo_id,content_hash,model,dimension,retrieval_capability);
