-- Incoming work is accepted into the current day's obligation only until 22:00.
-- Work already accepted by that time keeps its completion window until midnight.
UPDATE app_settings
SET setting_value = '22:00',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.shadow.shift-end'
  AND setting_value = '23:00';

UPDATE app_settings
SET setting_value = CAST(CAST(setting_value AS UNSIGNED) + 1 AS CHAR),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'workload.shadow.settings-revision';
