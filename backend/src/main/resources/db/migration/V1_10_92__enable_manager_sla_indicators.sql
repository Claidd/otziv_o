-- SLA timers are part of the manager control workflow and must be visible
-- immediately after the feature rollout. The setting remains reversible from
-- the gamification/rewards administration screen.
UPDATE app_settings
SET setting_value = 'true',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'manager.sla.enabled'
  AND setting_value <> 'true';

INSERT INTO app_settings (setting_key, setting_value, updated_at)
SELECT 'manager.sla.enabled', 'true', CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM app_settings
    WHERE setting_key = 'manager.sla.enabled'
);
