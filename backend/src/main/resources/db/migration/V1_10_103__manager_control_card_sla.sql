INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager.sla.target.control-card-minutes', '30', CURRENT_TIMESTAMP(6)),
    ('manager.sla.hard.control-card-minutes', '60', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = setting_key;
