-- Product policy changed from 60 to 30 days. Preserve deliberately customized
-- values while migrating installations that still carry the former default.
UPDATE app_settings
SET setting_value = '30',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'client.messages.payment-overdue-days'
  AND TRIM(setting_value) = '60';

INSERT INTO app_settings (setting_key, setting_value, updated_at)
SELECT 'client.messages.payment-overdue-days', '30', CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM app_settings
    WHERE setting_key = 'client.messages.payment-overdue-days'
);
