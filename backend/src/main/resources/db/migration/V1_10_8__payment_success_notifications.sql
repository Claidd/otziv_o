ALTER TABLE payment_links
    ADD COLUMN payment_success_notified_at DATETIME(6) NULL,
    ADD COLUMN payment_success_notification_error VARCHAR(512) NULL;
