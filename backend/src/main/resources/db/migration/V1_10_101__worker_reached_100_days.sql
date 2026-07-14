SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'reached_100'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN reached_100 BIT NOT NULL DEFAULT 0 AFTER checked',
    'SELECT ''worker_daily_performance.reached_100 exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'first_reached_100_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN first_reached_100_at DATETIME(6) NULL AFTER reached_100',
    'SELECT ''worker_daily_performance.first_reached_100_at exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_daily_performance'
      AND COLUMN_NAME = 'last_reached_100_at'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_daily_performance ADD COLUMN last_reached_100_at DATETIME(6) NULL AFTER first_reached_100_at',
    'SELECT ''worker_daily_performance.last_reached_100_at exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_performance_monthly'
      AND COLUMN_NAME = 'reached_100_days'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE worker_performance_monthly ADD COLUMN reached_100_days INT NOT NULL DEFAULT 0 AFTER checked_days',
    'SELECT ''worker_performance_monthly.reached_100_days exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
