CREATE TABLE IF NOT EXISTS worker_work_item_lifecycle (
    lifecycle_id BIGINT NOT NULL AUTO_INCREMENT,
    work_item_key VARCHAR(120) NOT NULL,
    worker_id BIGINT NOT NULL,
    worker_user_id BIGINT NULL,
    section_code VARCHAR(32) NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    item_id BIGINT NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    active BIT NOT NULL DEFAULT 1,
    excluded BIT NOT NULL DEFAULT 0,
    exclusion_reason VARCHAR(120) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lifecycle_id),
    UNIQUE KEY uk_worker_work_item_lifecycle_key (work_item_key),
    INDEX idx_worker_work_item_lifecycle_worker_open (worker_id, opened_at),
    INDEX idx_worker_work_item_lifecycle_worker_closed (worker_id, closed_at),
    INDEX idx_worker_work_item_lifecycle_active (active, worker_id, section_code),
    CONSTRAINT fk_worker_work_item_lifecycle_worker FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE,
    CONSTRAINT fk_worker_work_item_lifecycle_user FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS worker_daily_performance (
    daily_id BIGINT NOT NULL AUTO_INCREMENT,
    progress_date DATE NOT NULL,
    worker_id BIGINT NOT NULL,
    worker_user_id BIGINT NULL,
    worker_name VARCHAR(220) NULL,
    active_count BIGINT NOT NULL DEFAULT 0,
    completed_count BIGINT NOT NULL DEFAULT 0,
    total_count BIGINT NOT NULL DEFAULT 0,
    progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    checked BIT NOT NULL DEFAULT 0,
    first_completed_at DATETIME(6) NULL,
    last_completed_at DATETIME(6) NULL,
    average_close_seconds BIGINT NOT NULL DEFAULT 0,
    median_close_seconds BIGINT NOT NULL DEFAULT 0,
    p90_close_seconds BIGINT NOT NULL DEFAULT 0,
    load_score BIGINT NOT NULL DEFAULT 0,
    efficiency_score INT NOT NULL DEFAULT 0,
    aggregation_status VARCHAR(24) NOT NULL DEFAULT 'CALCULATED',
    finalized_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (daily_id),
    UNIQUE KEY uk_worker_daily_performance (progress_date, worker_id),
    INDEX idx_worker_daily_performance_worker_date (worker_id, progress_date),
    INDEX idx_worker_daily_performance_status_date (aggregation_status, progress_date),
    CONSTRAINT fk_worker_daily_performance_worker FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE,
    CONSTRAINT fk_worker_daily_performance_user FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS worker_performance_monthly (
    monthly_id BIGINT NOT NULL AUTO_INCREMENT,
    month_start DATE NOT NULL,
    worker_id BIGINT NOT NULL,
    worker_user_id BIGINT NULL,
    working_days INT NOT NULL DEFAULT 0,
    completed_count BIGINT NOT NULL DEFAULT 0,
    active_count BIGINT NOT NULL DEFAULT 0,
    total_count BIGINT NOT NULL DEFAULT 0,
    average_progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    checked_days INT NOT NULL DEFAULT 0,
    average_close_seconds BIGINT NOT NULL DEFAULT 0,
    median_close_seconds BIGINT NOT NULL DEFAULT 0,
    p90_close_seconds BIGINT NOT NULL DEFAULT 0,
    load_score BIGINT NOT NULL DEFAULT 0,
    average_efficiency_score DECIMAL(5,2) NOT NULL DEFAULT 0,
    closed_period BIT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (monthly_id),
    UNIQUE KEY uk_worker_performance_monthly (month_start, worker_id),
    INDEX idx_worker_performance_monthly_worker (worker_id, month_start),
    CONSTRAINT fk_worker_performance_monthly_worker FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE,
    CONSTRAINT fk_worker_performance_monthly_user FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO app_settings (setting_key, setting_value, updated_at) VALUES
    ('worker.progress.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('worker.progress.visible-roles', 'ADMIN,OWNER', CURRENT_TIMESTAMP(6)),
    ('worker.progress.raw-retention-days', '90', CURRENT_TIMESTAMP(6)),
    ('worker.progress.daily-retention-days', '400', CURRENT_TIMESTAMP(6)),
    ('worker.progress.cleanup-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('worker.progress.monthly-aggregate-enabled', 'true', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND INDEX_NAME = 'idx_reviews_worker_progress'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_worker_progress (review_worker, review_publish, review_vigul, review_publish_date, review_published_marked_at, review_id)',
    'SELECT ''idx_reviews_worker_progress exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_score_ledger'
      AND INDEX_NAME = 'idx_gamification_daily_actor_progress'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE gamification_score_ledger ADD INDEX idx_gamification_daily_actor_progress (actor_role, actor_user_id, source_event_created_at, points)',
    'SELECT ''idx_gamification_daily_actor_progress exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_orders_worker_progress'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_worker_progress (order_worker, order_complete, order_status, order_waiting_for_client, order_status_changed_at, order_id)',
    'SELECT ''idx_orders_worker_progress exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'business_audit_events'
      AND INDEX_NAME = 'idx_business_audit_worker_progress'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE business_audit_events ADD INDEX idx_business_audit_worker_progress (action, created_at, order_id)',
    'SELECT ''idx_business_audit_worker_progress exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bad_review_tasks'
      AND INDEX_NAME = 'idx_bad_review_tasks_worker_progress'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE bad_review_tasks ADD INDEX idx_bad_review_tasks_worker_progress (bad_review_task_worker, bad_review_task_status, bad_review_task_scheduled_date, bad_review_task_completed_date, bad_review_task_id)',
    'SELECT ''idx_bad_review_tasks_worker_progress exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'review_recovery_tasks'
      AND INDEX_NAME = 'idx_recovery_tasks_worker_progress'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE review_recovery_tasks ADD INDEX idx_recovery_tasks_worker_progress (review_recovery_task_worker, review_recovery_task_status, review_recovery_task_scheduled_date, review_recovery_task_completed_date, review_recovery_task_batch, review_recovery_task_id)',
    'SELECT ''idx_recovery_tasks_worker_progress exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
