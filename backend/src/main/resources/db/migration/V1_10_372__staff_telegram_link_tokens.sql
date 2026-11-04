-- One-time deep-link tokens for binding a staff member's private Telegram chat.
-- Replaces binding by typing a username to the bot (anyone knowing a login could bind).
-- Only the SHA-256 hash of the token is stored.
CREATE TABLE IF NOT EXISTS staff_telegram_link_tokens (
    token_hash CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (token_hash),
    KEY idx_staff_telegram_link_tokens_user (user_id),
    KEY idx_staff_telegram_link_tokens_expires (expires_at),
    CONSTRAINT fk_staff_telegram_link_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
