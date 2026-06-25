SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'resolution_action'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE worker_risk_incidents ADD COLUMN resolution_action VARCHAR(40) NULL AFTER details',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'resolved_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE worker_risk_incidents ADD COLUMN resolved_at DATETIME(6) NULL AFTER resolution_action',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'resolved_by_user_id'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE worker_risk_incidents ADD COLUMN resolved_by_user_id BIGINT NULL AFTER resolved_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'resolved_by_username'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE worker_risk_incidents ADD COLUMN resolved_by_username VARCHAR(150) NULL AFTER resolved_by_user_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
