SET @col_exists = (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'explanation_requested_at'
);
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD COLUMN explanation_requested_at DATETIME(6) NULL',
              'SELECT ''explanation_requested_at exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'explanation_prompted_at'
);
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD COLUMN explanation_prompted_at DATETIME(6) NULL',
              'SELECT ''explanation_prompted_at exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'worker_explanation'
);
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD COLUMN worker_explanation TEXT NULL',
              'SELECT ''worker_explanation exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'worker_explanation_at'
);
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD COLUMN worker_explanation_at DATETIME(6) NULL',
              'SELECT ''worker_explanation_at exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'worker_explanation_by_user_id'
);
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD COLUMN worker_explanation_by_user_id BIGINT NULL',
              'SELECT ''worker_explanation_by_user_id exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND index_name = 'idx_worker_risk_explanation_pending'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD INDEX idx_worker_risk_explanation_pending (worker_user_id, status, resolution_action, worker_explanation_at, explanation_prompted_at)',
              'SELECT ''idx_worker_risk_explanation_pending exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
