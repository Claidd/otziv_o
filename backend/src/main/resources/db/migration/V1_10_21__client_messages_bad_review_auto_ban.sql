INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.bad-review-auto-ban.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.bad-review-auto-ban.delay-days', '2', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
