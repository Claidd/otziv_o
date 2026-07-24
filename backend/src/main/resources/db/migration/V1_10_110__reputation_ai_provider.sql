INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('reputation.ai.provider', 'deepseek', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
