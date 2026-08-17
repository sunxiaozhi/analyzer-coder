-- Allow OpenAI-compatible embedding models to use their native output dimension.
-- Existing 64-dimensional vectors remain valid and keep their recorded dimension.

DROP INDEX IF EXISTS idx_chunk_embeddings_cosine;
DROP INDEX IF EXISTS idx_knowledge_embeddings_cosine;

ALTER TABLE vector_model_configs
    DROP CONSTRAINT IF EXISTS vector_model_configs_dimension_check;
ALTER TABLE vector_model_configs
    ADD CONSTRAINT vector_model_configs_dimension_check
    CHECK (dimension BETWEEN 1 AND 4096);

ALTER TABLE chunk_embeddings
    ALTER COLUMN model TYPE VARCHAR(200),
    ALTER COLUMN embedding TYPE vector USING embedding::vector;
ALTER TABLE chunk_embeddings
    DROP CONSTRAINT IF EXISTS chunk_embeddings_dimension_check;
ALTER TABLE chunk_embeddings
    ADD CONSTRAINT chunk_embeddings_dimension_check
    CHECK (dimension BETWEEN 1 AND 4096),
    ADD CONSTRAINT chunk_embeddings_vector_dimension_check
    CHECK (vector_dims(embedding) = dimension);

ALTER TABLE knowledge_card_embeddings
    ALTER COLUMN embedding TYPE vector USING embedding::vector;
ALTER TABLE knowledge_card_embeddings
    ADD COLUMN dimension INTEGER;
UPDATE knowledge_card_embeddings
SET dimension = vector_dims(embedding)
WHERE dimension IS NULL;
ALTER TABLE knowledge_card_embeddings
    ALTER COLUMN dimension SET NOT NULL,
    ADD CONSTRAINT knowledge_embeddings_dimension_check
    CHECK (dimension BETWEEN 1 AND 4096),
    ADD CONSTRAINT knowledge_embeddings_vector_dimension_check
    CHECK (vector_dims(embedding) = dimension);

COMMENT ON COLUMN knowledge_card_embeddings.dimension IS '向量维度';
