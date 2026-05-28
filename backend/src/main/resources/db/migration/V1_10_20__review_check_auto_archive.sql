INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.review-check.auto-archive.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.review-check.auto-archive-days', '30', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
