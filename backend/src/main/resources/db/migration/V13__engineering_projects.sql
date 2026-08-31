CREATE TABLE engineering_projects (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES accounts(id),
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_engineering_project_version CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_engineering_projects_name
    ON engineering_projects(normalized_name) WHERE deleted_at IS NULL;

CREATE TABLE engineering_project_repositories (
    project_id UUID NOT NULL REFERENCES engineering_projects(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE RESTRICT,
    service_name VARCHAR(100) NOT NULL,
    normalized_service_name VARCHAR(100) NOT NULL,
    added_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(project_id,repo_id),
    CONSTRAINT uq_engineering_project_service UNIQUE(project_id,normalized_service_name)
);

CREATE INDEX idx_engineering_project_repositories_repo
    ON engineering_project_repositories(repo_id,project_id);

CREATE TABLE engineering_project_contracts (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES engineering_projects(id) ON DELETE CASCADE,
    contract_key VARCHAR(120) NOT NULL,
    normalized_contract_key VARCHAR(120) NOT NULL,
    name VARCHAR(160) NOT NULL,
    provider_repo_id UUID NOT NULL,
    consumer_repo_id UUID NOT NULL,
    provider_snapshot_id UUID NOT NULL,
    consumer_snapshot_id UUID NOT NULL,
    provider_evidence_path VARCHAR(1000) NOT NULL,
    consumer_evidence_path VARCHAR(1000) NOT NULL,
    provider_content_fingerprint VARCHAR(64) NOT NULL,
    consumer_content_fingerprint VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_engineering_project_contract UNIQUE(project_id,normalized_contract_key),
    CONSTRAINT chk_engineering_contract_repositories CHECK(provider_repo_id<>consumer_repo_id),
    CONSTRAINT fk_engineering_contract_provider FOREIGN KEY(project_id,provider_repo_id)
        REFERENCES engineering_project_repositories(project_id,repo_id) ON DELETE RESTRICT,
    CONSTRAINT fk_engineering_contract_consumer FOREIGN KEY(project_id,consumer_repo_id)
        REFERENCES engineering_project_repositories(project_id,repo_id) ON DELETE RESTRICT
);

CREATE INDEX idx_engineering_project_contracts_provider
    ON engineering_project_contracts(provider_repo_id,project_id);
CREATE INDEX idx_engineering_project_contracts_consumer
    ON engineering_project_contracts(consumer_repo_id,project_id);

COMMENT ON TABLE engineering_projects IS '把多个真实仓库组织为一个可治理的工程项目';
COMMENT ON TABLE engineering_project_repositories IS '工程项目中的仓库及其显式服务身份';
COMMENT ON TABLE engineering_project_contracts IS '以两端当前代码路径和内容指纹验证的跨仓接口契约';
COMMENT ON COLUMN engineering_project_contracts.provider_content_fingerprint IS
    '创建或更新时对提供方当前路径全部 Chunk 内容哈希生成的稳定指纹';
COMMENT ON COLUMN engineering_project_contracts.consumer_content_fingerprint IS
    '创建或更新时对消费方当前路径全部 Chunk 内容哈希生成的稳定指纹';

UPDATE knowledge_cards
SET scope_payload = scope_payload
    || CASE WHEN NOT (scope_payload ? 'repositoryIds')
        THEN '{"repositoryIds":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'serviceNames')
        THEN '{"serviceNames":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'contractIds')
        THEN '{"contractIds":[]}'::jsonb ELSE '{}'::jsonb END
WHERE NOT (scope_payload ? 'repositoryIds')
   OR NOT (scope_payload ? 'serviceNames')
   OR NOT (scope_payload ? 'contractIds');

UPDATE knowledge_card_revisions
SET scope_payload = scope_payload
    || CASE WHEN NOT (scope_payload ? 'repositoryIds')
        THEN '{"repositoryIds":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'serviceNames')
        THEN '{"serviceNames":[]}'::jsonb ELSE '{}'::jsonb END
    || CASE WHEN NOT (scope_payload ? 'contractIds')
        THEN '{"contractIds":[]}'::jsonb ELSE '{}'::jsonb END
WHERE NOT (scope_payload ? 'repositoryIds')
   OR NOT (scope_payload ? 'serviceNames')
   OR NOT (scope_payload ? 'contractIds');

ALTER TABLE knowledge_cards ALTER COLUMN scope_payload SET DEFAULT
    '{"pathPatterns":[],"symbols":[],"modules":[],"repositoryIds":[],"serviceNames":[],"contractIds":[]}'::jsonb;
ALTER TABLE knowledge_card_revisions ALTER COLUMN scope_payload SET DEFAULT
    '{"pathPatterns":[],"symbols":[],"modules":[],"repositoryIds":[],"serviceNames":[],"contractIds":[]}'::jsonb;

ALTER TABLE knowledge_cards DROP CONSTRAINT chk_knowledge_scope_payload;
ALTER TABLE knowledge_cards ADD CONSTRAINT chk_knowledge_scope_payload CHECK (
    jsonb_typeof(scope_payload)='object'
    AND jsonb_typeof(scope_payload->'pathPatterns')='array'
    AND jsonb_typeof(scope_payload->'symbols')='array'
    AND jsonb_typeof(scope_payload->'modules')='array'
    AND jsonb_typeof(scope_payload->'repositoryIds')='array'
    AND jsonb_typeof(scope_payload->'serviceNames')='array'
    AND jsonb_typeof(scope_payload->'contractIds')='array'
);

ALTER TABLE knowledge_card_revisions ADD CONSTRAINT chk_knowledge_revision_scope_payload CHECK (
    jsonb_typeof(scope_payload)='object'
    AND jsonb_typeof(scope_payload->'pathPatterns')='array'
    AND jsonb_typeof(scope_payload->'symbols')='array'
    AND jsonb_typeof(scope_payload->'modules')='array'
    AND jsonb_typeof(scope_payload->'repositoryIds')='array'
    AND jsonb_typeof(scope_payload->'serviceNames')='array'
    AND jsonb_typeof(scope_payload->'contractIds')='array'
);

COMMENT ON COLUMN knowledge_cards.scope_payload IS
    '仓库内路径/符号/模块及工程项目中的仓库/服务/契约适用范围';
