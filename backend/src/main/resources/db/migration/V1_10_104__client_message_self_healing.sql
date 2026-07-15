INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.transient-retry-minutes', '15', CURRENT_TIMESTAMP(6)),
    ('client.messages.manual-control.failure-threshold', '3', CURRENT_TIMESTAMP(6)),
    ('client.messages.manual-control.after-minutes', '60', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
