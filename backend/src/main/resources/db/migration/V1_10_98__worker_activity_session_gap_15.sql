UPDATE app_settings
SET setting_value = '15',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'worker.progress.activity-session-gap-minutes'
  AND setting_value = '30';
