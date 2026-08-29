-- Durable, fenced delivery for common-invoice payment notifications.
-- CLIENT is one message per paid invoice; RECIPIENT is one message per
-- immutable actual-payment attribution (split receipts therefore remain
-- independent and idempotent).
CREATE TABLE common_invoice_payment_notification_outbox (
    delivery_id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT NOT NULL,
    notification_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    notification_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attribution_id BIGINT NULL,
    recipient_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recipient_user_id BIGINT NULL,
    amount_kopecks BIGINT NULL,
    invoice_title VARCHAR(180) NULL,
    order_count INT NULL,
    actor VARCHAR(150) NULL,
    confirmed_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    processing_owner VARCHAR(128) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_lease_until DATETIME(6) NULL,
    last_error VARCHAR(512) NULL,
    sent_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (delivery_id),
    UNIQUE KEY uq_common_invoice_notification (invoice_id, notification_kind, notification_key),
    UNIQUE KEY uq_common_invoice_recipient_attribution (attribution_id, notification_kind),
    INDEX idx_common_invoice_notification_due
        (sent_at, next_attempt_at, processing_lease_until, delivery_id),
    INDEX idx_common_invoice_notification_invoice (invoice_id, delivery_id),
    CONSTRAINT ck_common_invoice_notification_kind
        CHECK (notification_kind IN ('CLIENT', 'RECIPIENT')),
    CONSTRAINT ck_common_invoice_notification_recipient
        CHECK (
            (notification_kind = 'CLIENT'
                AND notification_key = 'CLIENT'
                AND attribution_id IS NULL)
            OR
            (notification_kind = 'RECIPIENT'
                AND attribution_id IS NOT NULL
                AND recipient_type IS NOT NULL
                AND recipient_user_id IS NOT NULL
                AND amount_kopecks > 0)
        ),
    CONSTRAINT ck_common_invoice_notification_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_common_invoice_notification_lease
        CHECK (
            (processing_token IS NULL
                AND processing_owner IS NULL
                AND processing_started_at IS NULL
                AND processing_lease_until IS NULL)
            OR
            (processing_token IS NOT NULL
                AND processing_owner IS NOT NULL
                AND processing_started_at IS NOT NULL
                AND processing_lease_until > processing_started_at)
        )
) ENGINE=InnoDB;

-- Recover client confirmations that were not durably marked on the day this
-- protection was introduced. Already-notified invoices are deliberately not
-- enqueued, so invoice #146 will not receive a duplicate client message.
INSERT IGNORE INTO common_invoice_payment_notification_outbox (
    invoice_id,
    notification_kind,
    notification_key
)
SELECT invoice.invoice_id,
       'CLIENT',
       'CLIENT'
FROM common_invoices invoice
WHERE invoice.status = 'PAID'
  AND invoice.payment_success_notified_at IS NULL
  AND invoice.paid_at >= '2026-08-28 00:00:00';

-- Common-invoice actual-recipient Telegram notifications did not exist before
-- this migration. Backfill only same-day LIVE receipts, including invoice #146,
-- so the reported missing notification is repaired without messaging old data.
INSERT IGNORE INTO common_invoice_payment_notification_outbox (
    invoice_id,
    notification_kind,
    notification_key,
    attribution_id,
    recipient_type,
    recipient_user_id,
    amount_kopecks,
    invoice_title,
    order_count,
    actor,
    confirmed_at
)
SELECT attribution.common_invoice_id,
       'RECIPIENT',
       CONCAT('ATTRIBUTION:', attribution.id),
       attribution.id,
       attribution.actual_recipient_type,
       attribution.actual_recipient_user_id,
       attribution.amount_kopecks,
       invoice.title,
       (
           SELECT COUNT(*)
           FROM common_invoice_orders invoice_order
           WHERE invoice_order.invoice_id = invoice.invoice_id
             AND invoice_order.paid = 1
       ),
       attribution.actor,
       attribution.effective_at
FROM contractor_actual_payment_attributions attribution
JOIN common_invoices invoice
  ON invoice.invoice_id = attribution.common_invoice_id
WHERE attribution.source_kind = 'COMMON_INVOICE'
  AND attribution.accounting_mode = 'LIVE'
  AND attribution.actual_recipient_type IN ('OWNER', 'MANAGER', 'SPECIALIST')
  AND attribution.actual_recipient_user_id IS NOT NULL
  AND attribution.amount_kopecks > 0
  AND attribution.correction_of_id IS NULL
  AND attribution.created_at >= '2026-08-28 00:00:00';
