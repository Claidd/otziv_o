-- Prepare workload LIVE launch for a two-manager CANARY without enabling it.
-- The runtime remains disabled until workload.live.mode/apply-enabled are
-- changed through the guarded activation flow.
UPDATE app_settings
SET setting_value = '1',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.live.min-candidates-per-manager';

UPDATE app_settings
SET setting_value = '7',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.live.min-finalized-days';

UPDATE app_settings
SET setting_value = '2,3',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.live.canary-manager-ids';

UPDATE app_settings
SET setting_value = CAST(CAST(setting_value AS UNSIGNED) + 1 AS CHAR),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.live.settings-revision';
