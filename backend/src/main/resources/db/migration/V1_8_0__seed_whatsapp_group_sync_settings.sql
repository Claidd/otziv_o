INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('whatsapp.group-sync.enabled', 'true', CURRENT_TIMESTAMP),
    ('whatsapp.group-sync.interval-minutes', '30', CURRENT_TIMESTAMP),
    ('whatsapp.group-sync.last-run-at', '', CURRENT_TIMESTAMP),
    ('whatsapp.group-sync.last-linked-count', '0', CURRENT_TIMESTAMP);
