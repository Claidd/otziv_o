UPDATE app_settings
SET setting_value = '90',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'archive.orders.retention.days'
  AND setting_value = '60';
