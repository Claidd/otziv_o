INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('performers.rollout.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('performers.rollout.city-ids', '', CURRENT_TIMESTAMP(6)),
    ('performers.rollout.product-ids', '', CURRENT_TIMESTAMP(6));
