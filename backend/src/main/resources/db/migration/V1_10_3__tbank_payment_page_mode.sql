INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('payments.tbank.payment-page-mode', 'SBP_PRIMARY', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
