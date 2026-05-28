CREATE TABLE IF NOT EXISTS mobile_push_tokens (
    mobile_push_token_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL,
    platform VARCHAR(32) NULL,
    device_id VARCHAR(128) NULL,
    app_version VARCHAR(64) NULL,
    active BIT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    PRIMARY KEY (mobile_push_token_id),
    UNIQUE KEY uk_mobile_push_tokens_token (token),
    KEY idx_mobile_push_tokens_user_active (user_id, active),
    CONSTRAINT fk_mobile_push_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
);
