INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.whatsapp-auth.retry-hours', '2', CURRENT_TIMESTAMP(6)),
    ('client.messages.whatsapp-auth.alert-cooldown-hours', '12', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
