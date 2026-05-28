UPDATE app_settings
SET setting_value = 'false', updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key IN (
    'client.messages.live.enabled',
    'client.messages.payment-overdue.live-enabled'
);
