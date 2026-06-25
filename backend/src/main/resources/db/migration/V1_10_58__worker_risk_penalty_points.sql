SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND column_name = 'penalty_points'
);

SET @ddl := IF(
        @column_exists = 0,
        'ALTER TABLE worker_risk_incidents ADD COLUMN penalty_points INT NOT NULL DEFAULT 0',
        'SELECT 1'
            );

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
