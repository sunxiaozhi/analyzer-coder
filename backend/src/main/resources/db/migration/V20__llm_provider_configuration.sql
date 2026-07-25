CREATE TABLE encrypted_secret_versions (
    id UUID PRIMARY KEY,
    cipher_text TEXT NOT NULL,
    iv TEXT NOT NULL,
    secret_digest CHAR(64) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE SEQUENCE llm_provider_config_version_seq START WITH 1;

CREATE TABLE llm_provider_configs (
    id UUID PRIMARY KEY,
    config_version BIGINT NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    base_url TEXT NOT NULL,
    model VARCHAR(200) NOT NULL,
    connect_timeout_ms INTEGER NOT NULL,
    request_timeout_ms INTEGER NOT NULL,
    max_output_tokens INTEGER NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    streaming_enabled BOOLEAN NOT NULL,
    secret_version_id UUID REFERENCES encrypted_secret_versions(id),
    fingerprint CHAR(64) NOT NULL,
    created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_llm_provider_type CHECK (provider_type IN ('OPENAI_COMPATIBLE')),
    CONSTRAINT chk_llm_connect_timeout CHECK (connect_timeout_ms BETWEEN 1000 AND 10000),
    CONSTRAINT chk_llm_request_timeout CHECK (request_timeout_ms BETWEEN 3000 AND 120000),
    CONSTRAINT chk_llm_max_output_tokens CHECK (max_output_tokens BETWEEN 1 AND 32768),
    CONSTRAINT chk_llm_temperature CHECK (temperature BETWEEN 0 AND 2)
);

CREATE TABLE llm_provider_runtime_states (
    config_id UUID PRIMARY KEY REFERENCES llm_provider_configs(id) ON DELETE CASCADE,
    availability VARCHAR(30) NOT NULL DEFAULT 'UNTESTED',
    latest_check_id UUID,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    breaker_state VARCHAR(20) NOT NULL DEFAULT 'CLOSED',
    breaker_opened_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_llm_availability CHECK (availability IN ('UNTESTED','AVAILABLE','DEGRADED','UNAVAILABLE')),
    CONSTRAINT chk_llm_breaker CHECK (breaker_state IN ('CLOSED','OPEN'))
);

CREATE TABLE llm_provider_activation (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    active_config_id UUID REFERENCES llm_provider_configs(id),
    activation_version BIGINT NOT NULL DEFAULT 0,
    activated_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    activated_at TIMESTAMPTZ,
    CONSTRAINT chk_llm_activation_singleton CHECK (singleton_id = 1)
);

INSERT INTO llm_provider_activation(singleton_id) VALUES (1);

CREATE TABLE llm_connectivity_checks (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    config_id UUID REFERENCES llm_provider_configs(id) ON DELETE SET NULL,
    fingerprint CHAR(64) NOT NULL,
    endpoint_host VARCHAR(255) NOT NULL,
    model VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    availability VARCHAR(30) NOT NULL DEFAULT 'UNTESTED',
    current_stage VARCHAR(50),
    stage_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_code VARCHAR(80),
    error_summary VARCHAR(500),
    total_duration_ms BIGINT,
    connect_duration_ms BIGINT,
    first_token_duration_ms BIGINT,
    request_id UUID NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_llm_check_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELED')),
    CONSTRAINT chk_llm_check_availability CHECK (availability IN ('UNTESTED','AVAILABLE','DEGRADED','UNAVAILABLE'))
);

CREATE INDEX idx_llm_configs_created ON llm_provider_configs(created_at DESC);
CREATE INDEX idx_llm_checks_actor_created ON llm_connectivity_checks(actor_id, created_at DESC);
CREATE INDEX idx_llm_checks_fingerprint_created ON llm_connectivity_checks(fingerprint, created_at DESC);

ALTER TABLE llm_provider_runtime_states
    ADD CONSTRAINT fk_llm_runtime_latest_check
    FOREIGN KEY (latest_check_id) REFERENCES llm_connectivity_checks(id) ON DELETE SET NULL;
