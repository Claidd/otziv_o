SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_account_walk_delay_days'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_account_walk_delay_days INT NOT NULL DEFAULT 0',
    'SELECT ''review_account_walk_delay_days exists on reviews'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_reviews'
      AND COLUMN_NAME = 'review_account_walk_delay_days'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE archive_reviews ADD COLUMN review_account_walk_delay_days INT NOT NULL DEFAULT 0',
    'SELECT ''review_account_walk_delay_days exists on archive_reviews'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('review.account.walked-counter-threshold', '3', CURRENT_TIMESTAMP(6)),
    ('review.account.walk-delay-days', '2', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
