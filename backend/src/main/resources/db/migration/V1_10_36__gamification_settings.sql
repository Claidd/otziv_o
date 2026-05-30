CREATE TABLE IF NOT EXISTS gamification_settings (
    setting_key VARCHAR(120) NOT NULL,
    setting_value VARCHAR(500) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO gamification_settings (setting_key, setting_value, updated_at)
VALUES
    ('gamification.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.worker.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('gamification.manager.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('gamification.operator.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('gamification.marketolog.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('gamification.show-in-cabinet', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.show-in-score', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.events-enabled', 'false', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_key = setting_key;
