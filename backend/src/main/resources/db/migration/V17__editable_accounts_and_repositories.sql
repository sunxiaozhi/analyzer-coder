ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS account_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS description VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS repository_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_version_positive CHECK (account_version > 0);

ALTER TABLE repositories
    ADD CONSTRAINT chk_repositories_version_positive CHECK (repository_version > 0);
