INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.payment-instruction-source', 'MANAGER_TEXT', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;

UPDATE app_settings
SET setting_value = 'https://o-ogo.ru', updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'client.messages.review-link-base-url'
  AND setting_value = 'https://o-ogo.ru/review/editReviews';
