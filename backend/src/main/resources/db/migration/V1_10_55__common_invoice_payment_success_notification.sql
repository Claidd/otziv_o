ALTER TABLE common_invoices
    ADD COLUMN payment_success_notified_at DATETIME(6) NULL AFTER paid_at,
    ADD COLUMN payment_success_notification_error VARCHAR(512) NULL AFTER payment_success_notified_at;

UPDATE common_invoices ci
JOIN common_invoice_payment_refs pr ON pr.invoice_id = ci.invoice_id
SET ci.status = 'NEEDS_ATTENTION',
    ci.next_reminder_at = NULL,
    ci.last_error = LEFT(
            CONCAT(
                    'tbank_payment_refunded: оплаченный общий счет получил статус ',
                    pr.status,
                    ' по T-Bank ссылке ',
                    COALESCE(NULLIF(pr.tbank_order_id, ''), '-'),
                    '/',
                    COALESCE(NULLIF(pr.tbank_payment_id, ''), '-'),
                    '; проверьте банк и оплату вручную'
            ),
            512
    )
WHERE ci.status = 'PAID'
  AND pr.status IN ('REFUNDED', 'PARTIAL_REFUNDED', 'REVERSED', 'PARTIAL_REVERSED', 'CANCELED');
