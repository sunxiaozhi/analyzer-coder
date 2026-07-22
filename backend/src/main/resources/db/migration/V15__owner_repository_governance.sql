ALTER TABLE repositories
  ADD COLUMN IF NOT EXISTS owner_account_id UUID REFERENCES accounts(id),
  ADD COLUMN IF NOT EXISTS normalized_name TEXT,
  ADD COLUMN IF NOT EXISTS ownership_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS repository_status TEXT NOT NULL DEFAULT 'READY',
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

UPDATE repositories SET normalized_name = LOWER(BTRIM(name)) WHERE normalized_name IS NULL;

DO $$
DECLARE
  migration_owner UUID;
  migration_owner_name TEXT := NULLIF(BTRIM('${repositoryMigrationOwner}'), '');
BEGIN
  IF EXISTS (SELECT 1 FROM repositories WHERE owner_account_id IS NULL) THEN
    IF migration_owner_name IS NULL THEN
      RAISE EXCEPTION 'Existing repositories require APP_REPOSITORY_MIGRATION_OWNER';
    END IF;
    SELECT id INTO migration_owner FROM accounts
    WHERE enabled=TRUE AND account_role='SUPER_ADMIN'
      AND LOWER(BTRIM(username))=LOWER(migration_owner_name) LIMIT 1;
    IF migration_owner IS NULL THEN
      RAISE EXCEPTION 'APP_REPOSITORY_MIGRATION_OWNER must identify an enabled SUPER_ADMIN';
    END IF;
    UPDATE repositories SET owner_account_id=migration_owner WHERE owner_account_id IS NULL;
  END IF;
END $$;

ALTER TABLE repositories ALTER COLUMN owner_account_id SET NOT NULL, ALTER COLUMN normalized_name SET NOT NULL;
DROP INDEX IF EXISTS uq_repositories_name_normalized;
CREATE UNIQUE INDEX IF NOT EXISTS uq_repositories_owner_normalized_name ON repositories(owner_account_id,normalized_name) WHERE deleted_at IS NULL;

ALTER TABLE repository_permissions DROP CONSTRAINT IF EXISTS repository_permissions_permission_level_check;
ALTER TABLE repository_permissions ADD CONSTRAINT repository_permissions_permission_level_check CHECK(permission_level IN ('READ','MAINTAIN','MANAGE'));
DELETE FROM repository_permissions p USING repositories r WHERE p.repo_id=r.id AND p.account_id=r.owner_account_id;

CREATE TABLE IF NOT EXISTS repository_governance_locks(
  repo_id UUID PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
  lock_version BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS repository_credentials(
  id UUID PRIMARY KEY,
  repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  credential_type TEXT NOT NULL,
  display_name TEXT NOT NULL,
  encrypted_secret TEXT NOT NULL,
  masked_value TEXT NOT NULL,
  credential_version BIGINT NOT NULL DEFAULT 1,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  last_validated_at TIMESTAMP,
  created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_repository_credentials_repo ON repository_credentials(repo_id,created_at DESC);
CREATE TABLE IF NOT EXISTS repository_deletion_tombstones(
  repository_id UUID PRIMARY KEY,
  deleted_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
  deleted_at TIMESTAMP NOT NULL,
  cleanup_status TEXT NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  last_error_code TEXT
);
CREATE INDEX IF NOT EXISTS idx_repositories_owner_status ON repositories(owner_account_id,repository_status,created_at DESC);