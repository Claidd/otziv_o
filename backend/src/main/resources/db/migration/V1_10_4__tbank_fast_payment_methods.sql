INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('payments.tbank.tpay-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('payments.tbank.sberpay-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('payments.tbank.mirpay-enabled', 'false', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
