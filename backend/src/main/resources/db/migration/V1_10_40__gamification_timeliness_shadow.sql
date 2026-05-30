SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_events'
      AND COLUMN_NAME = 'planned_date'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_events ADD COLUMN planned_date DATE NULL',
    'SELECT ''gamification_events.planned_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_events'
      AND COLUMN_NAME = 'actual_date'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_events ADD COLUMN actual_date DATE NULL',
    'SELECT ''gamification_events.actual_date exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_events'
      AND COLUMN_NAME = 'delay_days'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_events ADD COLUMN delay_days INT NULL',
    'SELECT ''gamification_events.delay_days exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_events'
      AND COLUMN_NAME = 'timeliness_bucket'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_events ADD COLUMN timeliness_bucket VARCHAR(32) NULL',
    'SELECT ''gamification_events.timeliness_bucket exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_events'
      AND COLUMN_NAME = 'timeliness_multiplier'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_events ADD COLUMN timeliness_multiplier DECIMAL(5,2) NULL',
    'SELECT ''gamification_events.timeliness_multiplier exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_score_ledger'
      AND COLUMN_NAME = 'base_points'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_score_ledger ADD COLUMN base_points INT NULL',
    'SELECT ''gamification_score_ledger.base_points exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_score_ledger'
      AND COLUMN_NAME = 'timeliness_multiplier'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_score_ledger ADD COLUMN timeliness_multiplier DECIMAL(5,2) NULL',
    'SELECT ''gamification_score_ledger.timeliness_multiplier exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_score_ledger'
      AND COLUMN_NAME = 'delay_days'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_score_ledger ADD COLUMN delay_days INT NULL',
    'SELECT ''gamification_score_ledger.delay_days exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_score_ledger'
      AND COLUMN_NAME = 'timeliness_bucket'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE gamification_score_ledger ADD COLUMN timeliness_bucket VARCHAR(32) NULL',
    'SELECT ''gamification_score_ledger.timeliness_bucket exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'gamification_score_ledger'
      AND INDEX_NAME = 'idx_gamification_score_ledger_timeliness'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE gamification_score_ledger ADD INDEX idx_gamification_score_ledger_timeliness (timeliness_bucket, delay_days)',
    'SELECT ''idx_gamification_score_ledger_timeliness exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
