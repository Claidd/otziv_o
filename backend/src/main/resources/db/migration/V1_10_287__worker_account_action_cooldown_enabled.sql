-- Keep the duration independent from the switch; existing installations remain enabled.
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('worker.account-action.cooldown-enabled', 'true', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
