INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.payment-invoice-retry.delay-hours', '2', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
