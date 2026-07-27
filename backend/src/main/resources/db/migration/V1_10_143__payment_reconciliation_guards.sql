ALTER TABLE payment_links
    ADD COLUMN payment_success_notification_retry_eligible TINYINT(1) NOT NULL DEFAULT 0
        AFTER payment_success_notification_error;

ALTER TABLE archive_payment_links
    ADD COLUMN payment_success_notification_retry_eligible TINYINT(1) NOT NULL DEFAULT 0
        AFTER payment_success_notification_error;

UPDATE payment_links
SET payment_success_notification_retry_eligible = 1
WHERE status = 'CONFIRMED'
  AND payment_success_notified_at IS NULL
  AND payment_success_notification_error IS NOT NULL;

CREATE INDEX idx_payment_links_bank_reconciliation
    ON payment_links (status, updated_at, id);

CREATE INDEX idx_payment_links_receipt_sla
    ON payment_links (payment_method, status, receipt_status, paid_at, id);

CREATE INDEX idx_archive_payment_links_receipt_sla
    ON archive_payment_links (payment_method, status, receipt_status, paid_at, id);
