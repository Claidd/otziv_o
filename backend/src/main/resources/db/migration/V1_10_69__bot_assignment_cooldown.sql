SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bots'
      AND COLUMN_NAME = 'bot_cooldown_until'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE bots ADD COLUMN bot_cooldown_until DATE NULL',
    'SELECT ''bot_cooldown_until exists on bots'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('review.account.cooldown-days', '2', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
