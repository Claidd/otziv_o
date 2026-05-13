CREATE TABLE IF NOT EXISTS reputation_ai_prompt_overrides (
    prompt_key VARCHAR(128) NOT NULL,
    content LONGTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (prompt_key)
);
