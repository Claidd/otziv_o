INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('archive.orders.retention.days', '60', CURRENT_TIMESTAMP(6)),
    ('archive.orders.batch.size', '500', CURRENT_TIMESTAMP(6)),
    ('archive.orders.schedule.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('archive.orders.run.mode', 'dry-run', CURRENT_TIMESTAMP(6)),
    ('archive.orders.reason', 'scheduled-orders-retention-dry-run', CURRENT_TIMESTAMP(6));
