INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('telegram.reports.morning.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('telegram.reports.morning.time', '11:30', CURRENT_TIMESTAMP(6)),
    ('telegram.reports.evening.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('telegram.reports.evening.time', '22:00', CURRENT_TIMESTAMP(6)),
    ('telegram.reports.zone', 'Asia/Irkutsk', CURRENT_TIMESTAMP(6));
