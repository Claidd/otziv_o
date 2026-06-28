CREATE TABLE IF NOT EXISTS worker_credential_preparations (
    preparation_id BIGINT NOT NULL AUTO_INCREMENT,
    worker_user_id BIGINT NOT NULL,
    scope VARCHAR(30) NOT NULL,
    review_id BIGINT NOT NULL,
    bot_id BIGINT NULL,
    login_copied_at DATETIME(6) NULL,
    password_copied_at DATETIME(6) NULL,
    source_page VARCHAR(80) NULL,
    source_entry VARCHAR(80) NULL,
    source_section VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (preparation_id),
    UNIQUE KEY uk_worker_credential_preparation_worker_scope (worker_user_id, scope),
    INDEX idx_worker_credential_preparation_review (review_id),
    INDEX idx_worker_credential_preparation_updated (updated_at)
);
