ALTER TABLE payment_profiles
    ADD COLUMN manual_payment_type VARCHAR(32) NOT NULL DEFAULT 'MOBILE_BANK',
    ADD COLUMN manual_payment_url VARCHAR(512) NOT NULL DEFAULT 'https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR',
    ADD COLUMN manual_payment_button_label VARCHAR(80) NOT NULL DEFAULT 'Оплатить через Альфа-Банк';

UPDATE payment_profiles
SET manual_payment_type = 'MOBILE_BANK'
WHERE manual_phone IS NOT NULL
  AND TRIM(manual_phone) <> ''
  AND manual_recipient_name IS NOT NULL
  AND TRIM(manual_recipient_name) <> '';

ALTER TABLE manual_payment_tasks
    MODIFY manual_phone VARCHAR(32) NULL,
    MODIFY manual_recipient_name VARCHAR(160) NULL,
    ADD COLUMN manual_payment_type VARCHAR(32) NOT NULL DEFAULT 'MOBILE_BANK',
    ADD COLUMN manual_payment_url VARCHAR(512) NOT NULL DEFAULT 'https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR',
    ADD COLUMN manual_payment_button_label VARCHAR(80) NOT NULL DEFAULT 'Оплатить через Альфа-Банк';

UPDATE manual_payment_tasks
SET manual_payment_type = 'MOBILE_BANK'
WHERE manual_phone IS NOT NULL
  AND TRIM(manual_phone) <> ''
  AND manual_recipient_name IS NOT NULL
  AND TRIM(manual_recipient_name) <> '';

ALTER TABLE payment_links
    ADD COLUMN manual_payment_type VARCHAR(32) NULL,
    ADD COLUMN manual_payment_url VARCHAR(512) NULL,
    ADD COLUMN manual_payment_button_label VARCHAR(80) NULL;

UPDATE payment_links
SET manual_payment_type = 'MOBILE_BANK'
WHERE payment_method = 'MANUAL_MOBILE_BANK'
  AND manual_payment_type IS NULL;
