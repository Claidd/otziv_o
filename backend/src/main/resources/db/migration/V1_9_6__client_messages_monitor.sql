INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('client.messages.monitor.enabled', 'false', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'scheduled_client_message_attempts'
      AND INDEX_NAME = 'idx_scheduled_attempts_status_time'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE scheduled_client_message_attempts ADD INDEX idx_scheduled_attempts_status_time (attempt_status, attempted_at)',
    'SELECT ''idx_scheduled_attempts_status_time exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'scheduled_client_message_attempts'
      AND INDEX_NAME = 'idx_scheduled_attempts_scenario_status_time'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE scheduled_client_message_attempts ADD INDEX idx_scheduled_attempts_scenario_status_time (scenario, attempt_status, attempted_at)',
    'SELECT ''idx_scheduled_attempts_scenario_status_time exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
