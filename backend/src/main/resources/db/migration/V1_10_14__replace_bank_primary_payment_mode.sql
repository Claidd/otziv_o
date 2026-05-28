UPDATE app_settings
SET setting_value = 'SBP_PAY_ONLY',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'payments.tbank.payment-page-mode'
  AND setting_value = 'BANK_PRIMARY';
