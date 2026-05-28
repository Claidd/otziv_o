INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.error-protection.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.error-protection.threshold', '20', CURRENT_TIMESTAMP(6)),
    ('client.messages.error-protection.window-minutes', '10', CURRENT_TIMESTAMP(6)),
    ('client.messages.error-protection.cooldown-minutes', '60', CURRENT_TIMESTAMP(6)),
    ('client.messages.paused-until', '', CURRENT_TIMESTAMP(6)),
    ('client.messages.pause-reason', '', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
