ALTER TABLE accounts ADD COLUMN last_repository_id UUID;

ALTER TABLE accounts ADD CONSTRAINT fk_accounts_last_repository
    FOREIGN KEY (last_repository_id) REFERENCES repositories(id) ON DELETE SET NULL;
CREATE INDEX idx_accounts_last_repository ON accounts(last_repository_id);
