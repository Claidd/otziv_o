SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'first_activity_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN first_activity_at DATETIME(6) NULL AFTER p90_close_seconds',
    'SELECT ''worker_daily_performance.first_activity_at exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'last_activity_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN last_activity_at DATETIME(6) NULL AFTER first_activity_at',
    'SELECT ''worker_daily_performance.last_activity_at exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'active_work_seconds'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN active_work_seconds BIGINT NOT NULL DEFAULT 0 AFTER last_activity_at',
    'SELECT ''worker_daily_performance.active_work_seconds exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'work_window_seconds'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN work_window_seconds BIGINT NOT NULL DEFAULT 0 AFTER active_work_seconds',
    'SELECT ''worker_daily_performance.work_window_seconds exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'activity_events'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN activity_events BIGINT NOT NULL DEFAULT 0 AFTER work_window_seconds',
    'SELECT ''worker_daily_performance.activity_events exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_performance_monthly'
      AND COLUMN_NAME = 'active_work_seconds'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_performance_monthly ADD COLUMN active_work_seconds BIGINT NOT NULL DEFAULT 0 AFTER p90_close_seconds',
    'SELECT ''worker_performance_monthly.active_work_seconds exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_performance_monthly'
      AND COLUMN_NAME = 'average_work_window_seconds'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_performance_monthly ADD COLUMN average_work_window_seconds BIGINT NOT NULL DEFAULT 0 AFTER active_work_seconds',
    'SELECT ''worker_performance_monthly.average_work_window_seconds exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_performance_monthly'
      AND COLUMN_NAME = 'activity_events'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_performance_monthly ADD COLUMN activity_events BIGINT NOT NULL DEFAULT 0 AFTER average_work_window_seconds',
    'SELECT ''worker_performance_monthly.activity_events exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO app_settings (setting_key, setting_value, updated_at) VALUES
    ('worker.progress.activity-session-gap-minutes', '30', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
