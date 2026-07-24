CREATE TABLE IF NOT EXISTS manager_team_daily_progress (
    manager_team_daily_progress_id BIGINT NOT NULL AUTO_INCREMENT,
    progress_date DATE NOT NULL,
    manager_id BIGINT NOT NULL,
    manager_user_id BIGINT NULL,
    worker_count INT NOT NULL DEFAULT 0,
    workers_at_100 INT NOT NULL DEFAULT 0,
    completed_count BIGINT NOT NULL DEFAULT 0,
    total_count BIGINT NOT NULL DEFAULT 0,
    progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    reached_100 BIT NOT NULL DEFAULT 0,
    captured_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (manager_team_daily_progress_id),
    UNIQUE KEY uk_manager_team_daily_progress (progress_date, manager_id),
    INDEX idx_manager_team_daily_progress_manager_date (manager_id, progress_date),
    CONSTRAINT fk_manager_team_daily_progress_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id) ON DELETE CASCADE,
    CONSTRAINT fk_manager_team_daily_progress_user
        FOREIGN KEY (manager_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
