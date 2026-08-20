-- 阶段 4.2:会话持久化与登录失败限制。

CREATE TABLE auth_sessions (
    token_hash  VARCHAR(128) PRIMARY KEY,
    username    VARCHAR(128) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX auth_sessions_expires_at_idx ON auth_sessions (expires_at);
CREATE INDEX auth_sessions_username_idx ON auth_sessions (username);

CREATE TABLE login_attempts (
    username         VARCHAR(128) PRIMARY KEY,
    failure_count    INTEGER NOT NULL DEFAULT 0,
    first_failure_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_until     TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX login_attempts_locked_until_idx ON login_attempts (locked_until);
