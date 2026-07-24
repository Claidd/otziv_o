CREATE TABLE IF NOT EXISTS end_of_day_achievement_results (
    end_of_day_achievement_result_id BIGINT NOT NULL AUTO_INCREMENT,
    result_date DATE NOT NULL,
    actor_role VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    eligible_count BIGINT NOT NULL DEFAULT 0,
    completed_count BIGINT NOT NULL DEFAULT 0,
    progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    ignored_late_count BIGINT NOT NULL DEFAULT 0,
    reached_100 BIT NOT NULL DEFAULT 0,
    streak_days INT NOT NULL DEFAULT 0,
    telegram_notified_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (end_of_day_achievement_result_id),
    UNIQUE KEY uk_end_of_day_achievement_actor_date (result_date, actor_role, actor_id),
    INDEX idx_end_of_day_achievement_streak (actor_role, actor_id, result_date),
    CONSTRAINT fk_end_of_day_achievement_user
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
