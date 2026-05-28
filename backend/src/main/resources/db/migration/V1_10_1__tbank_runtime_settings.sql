INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('payments.tbank.runtime-mode', 'TEST', CURRENT_TIMESTAMP(6)),
    ('payments.tbank.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('payments.tbank.payment-links-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('payments.tbank.manager-ui-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('payments.tbank.apply-confirmed-payments', 'false', CURRENT_TIMESTAMP(6)),
    ('client.messages.payment-instruction-source', 'MANAGER_TEXT', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
