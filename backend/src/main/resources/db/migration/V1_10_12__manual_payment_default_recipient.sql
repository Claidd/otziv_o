UPDATE payment_profiles
SET manual_recipient_name = 'Сивохин И.И.'
WHERE manual_recipient_name IS NULL
   OR TRIM(manual_recipient_name) = ''
   OR TRIM(manual_recipient_name) = 'Оплатить через Альфа-Банк';

UPDATE manual_payment_tasks
SET manual_recipient_name = 'Сивохин И.И.'
WHERE manual_recipient_name IS NULL
   OR TRIM(manual_recipient_name) = ''
   OR TRIM(manual_recipient_name) = 'Оплатить через Альфа-Банк';

UPDATE payment_links
SET manual_recipient_name = 'Сивохин И.И.'
WHERE payment_method = 'MANUAL_EXTERNAL_LINK'
  AND (
    manual_recipient_name IS NULL
    OR TRIM(manual_recipient_name) = ''
    OR TRIM(manual_recipient_name) = 'Оплатить через Альфа-Банк'
  );
