-- The gamification module is not used in the current product rollout. Disable
-- both presentation and background event/scoring switches explicitly; data is
-- retained so the module can be re-enabled later without destructive restore.
INSERT INTO gamification_settings (setting_key, setting_value, updated_at)
VALUES
    ('gamification.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.worker.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.manager.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.operator.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.marketolog.enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.show-in-cabinet', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.show-in-score', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.events-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('gamification.shadow-scoring.enabled', 'false', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);
