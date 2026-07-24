INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('manager-control.unanswered-client-messages.warning-minutes', '0', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);
