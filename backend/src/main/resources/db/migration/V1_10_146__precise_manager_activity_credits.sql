INSERT INTO app_settings (setting_key, setting_value, updated_at) VALUES
    ('manager.summary.heartbeat-credit-seconds', '60', CURRENT_TIMESTAMP(6)),
    ('manager.summary.active-heartbeat-credit-seconds', '30', CURRENT_TIMESTAMP(6)),
    ('manager.summary.interaction-credit-seconds', '30', CURRENT_TIMESTAMP(6)),
    ('manager.summary.action-credit-seconds', '15', CURRENT_TIMESTAMP(6)),
    ('manager.summary.message-credit-seconds', '60', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
