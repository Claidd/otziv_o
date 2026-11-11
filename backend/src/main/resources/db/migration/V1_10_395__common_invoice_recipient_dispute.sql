-- A recipient may dispute a manually confirmed common-invoice receipt from its Telegram notice.
-- The first dispute is kept on the notice itself, so a repeated press never raises a second alarm.
ALTER TABLE common_invoice_payment_notification_outbox
    ADD COLUMN disputed_at DATETIME(6) NULL,
    ADD COLUMN disputed_by_telegram_user_id BIGINT NULL;
