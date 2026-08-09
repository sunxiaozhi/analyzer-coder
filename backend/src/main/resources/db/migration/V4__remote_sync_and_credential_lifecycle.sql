ALTER TABLE repositories ADD COLUMN remote_url TEXT;

ALTER TABLE git_credentials
    ADD COLUMN last_validation_error TEXT,
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN disabled_at TIMESTAMPTZ;

CREATE INDEX idx_git_credentials_expiry ON git_credentials(status, expires_at);

COMMENT ON COLUMN repositories.remote_url IS '远程 Git/GitLab HTTPS 克隆地址';
COMMENT ON COLUMN git_credentials.expires_at IS '可选的凭据过期时间';
COMMENT ON COLUMN git_credentials.last_validation_error IS '脱敏后的最近检测失败原因';
