CREATE TABLE login_captcha_challenges (
 id UUID PRIMARY KEY, username_normalized TEXT NOT NULL, answer_hash VARCHAR(64) NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_login_captcha_user ON login_captcha_challenges(username_normalized,created_at DESC);
