ALTER TABLE repository_credentials RENAME TO git_credentials;

ALTER TABLE git_credentials
    RENAME COLUMN repo_id TO legacy_repo_id;

ALTER TABLE git_credentials
    ALTER COLUMN legacy_repo_id DROP NOT NULL;

ALTER TABLE git_credentials
    ADD COLUMN server_url TEXT,
    ADD COLUMN username TEXT,
    ADD COLUMN secret_iv TEXT,
    ADD COLUMN secret_digest TEXT,
    ADD COLUMN encryption_algorithm TEXT NOT NULL DEFAULT 'AES-256-GCM',
    ADD COLUMN updated_by UUID REFERENCES accounts(id) ON DELETE SET NULL;

UPDATE git_credentials
SET server_url = '',
    secret_iv = '',
    secret_digest = ''
WHERE server_url IS NULL OR secret_iv IS NULL OR secret_digest IS NULL;

ALTER TABLE git_credentials
    ALTER COLUMN server_url SET NOT NULL,
    ALTER COLUMN secret_iv SET NOT NULL,
    ALTER COLUMN secret_digest SET NOT NULL;

ALTER TABLE git_credentials
    ADD CONSTRAINT chk_git_credentials_type
        CHECK (credential_type IN ('GIT_HTTP_TOKEN', 'GITLAB_PAT')),
    ADD CONSTRAINT chk_git_credentials_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'INVALID'));

CREATE INDEX idx_git_credentials_owner
    ON git_credentials(created_by, status, created_at DESC);

CREATE TABLE repository_credential_bindings (
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    credential_id UUID NOT NULL REFERENCES git_credentials(id) ON DELETE RESTRICT,
    usage_type TEXT NOT NULL DEFAULT 'CLONE',
    bound_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (repository_id, usage_type),
    CONSTRAINT chk_repository_credential_usage CHECK (usage_type IN ('CLONE'))
);

CREATE INDEX idx_repository_credential_bindings_credential
    ON repository_credential_bindings(credential_id, created_at DESC);

COMMENT ON TABLE git_credentials IS '可复用的加密 Git/GitLab HTTPS 凭据';
COMMENT ON COLUMN git_credentials.legacy_repo_id IS '迁移前的仓库绑定，仅用于兼容旧数据';
COMMENT ON TABLE repository_credential_bindings IS '仓库与可复用凭据的用途绑定';
