CREATE TABLE IF NOT EXISTS worker_network_violation_episodes (
    violation_id BIGINT NOT NULL AUTO_INCREMENT,
    worker_user_id BIGINT NOT NULL,
    worker_username VARCHAR(150) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    scope_code VARCHAR(64) NOT NULL,
    access_mode VARCHAR(16) NOT NULL,
    access_result VARCHAR(24) NOT NULL,
    episode_slot DATETIME(6) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    attempt_count BIGINT NOT NULL DEFAULT 1,
    provider VARCHAR(180) NULL,
    ip_prefix VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (violation_id),
    UNIQUE KEY uk_worker_network_violation_episode (
        worker_user_id,
        reason_code,
        scope_code,
        episode_slot
    ),
    INDEX idx_worker_network_violation_user_period (worker_user_id, last_seen_at, first_seen_at),
    INDEX idx_worker_network_violation_period (last_seen_at, reason_code),
    CONSTRAINT fk_worker_network_violation_user
        FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
