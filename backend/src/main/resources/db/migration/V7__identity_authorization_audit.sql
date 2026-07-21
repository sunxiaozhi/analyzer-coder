CREATE TABLE IF NOT EXISTS accounts (
  id UUID PRIMARY KEY,
  username TEXT NOT NULL,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  account_role TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP,
  temporary_password_expires_at TIMESTAMP,
  last_login_at TIMESTAMP,
  last_login_ip TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_username_normalized
  ON accounts (LOWER(BTRIM(username)));

CREATE TABLE IF NOT EXISTS login_sessions (
  token_hash TEXT PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  csrf_token TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_login_sessions_account ON login_sessions(account_id);
CREATE INDEX IF NOT EXISTS idx_login_sessions_expiry ON login_sessions(expires_at);

CREATE TABLE IF NOT EXISTS repository_permissions (
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  repo_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  permission_level TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (account_id, repo_id)
);

CREATE INDEX IF NOT EXISTS idx_repository_permissions_repo ON repository_permissions(repo_id);

CREATE TABLE IF NOT EXISTS audit_events (
  id UUID PRIMARY KEY,
  actor_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
  target_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
  target_repo_id UUID REFERENCES repositories(id) ON DELETE SET NULL,
  event_type TEXT NOT NULL,
  result TEXT NOT NULL,
  request_id UUID NOT NULL,
  source_ip TEXT,
  details JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_events_created ON audit_events(created_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_audit_events_actor ON audit_events(actor_account_id, created_at DESC);
