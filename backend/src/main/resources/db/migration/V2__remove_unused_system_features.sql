DELETE FROM system_settings
WHERE setting_key IN (
    'embeddingModel',
    'llmProvider',
    'maxSearchResults',
    'excludedPatterns',
    'backupRetentionDays'
);

DROP TABLE backup_sets;

CREATE TABLE vector_model_configs (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    provider_type VARCHAR(40) NOT NULL CHECK (provider_type IN ('LOCAL_HASH','OPENAI_COMPATIBLE')),
    base_url TEXT,
    model VARCHAR(200) NOT NULL UNIQUE,
    dimension INTEGER NOT NULL CHECK (dimension = 64),
    request_timeout_ms INTEGER NOT NULL DEFAULT 30000 CHECK (request_timeout_ms BETWEEN 3000 AND 120000),
    secret_version_id UUID REFERENCES encrypted_secret_versions(id),
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE vector_model_activation (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    active_config_id UUID NOT NULL REFERENCES vector_model_configs(id),
    activation_version BIGINT NOT NULL DEFAULT 0,
    activated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO vector_model_configs(id,name,provider_type,model,dimension)
VALUES('00000000-0000-0000-0000-000000000064','内置向量模型','LOCAL_HASH','local-hash-64',64);

INSERT INTO vector_model_activation(singleton_id,active_config_id)
VALUES(1,'00000000-0000-0000-0000-000000000064');

ALTER TABLE knowledge_card_embeddings
    ADD COLUMN model VARCHAR(200) NOT NULL DEFAULT 'local-hash-64';
