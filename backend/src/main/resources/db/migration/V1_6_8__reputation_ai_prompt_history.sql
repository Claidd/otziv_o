CREATE TABLE IF NOT EXISTS reputation_ai_prompt_history (
    history_id BIGINT NOT NULL AUTO_INCREMENT,
    prompt_key VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(160) NULL,
    previous_content LONGTEXT NULL,
    content LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (history_id),
    INDEX idx_reputation_ai_prompt_history_key_created (prompt_key, created_at)
);
