ALTER TABLE common_invoices
    ADD COLUMN payment_method VARCHAR(32) NULL AFTER paid_at,
    ADD COLUMN manual_paid_by VARCHAR(160) NULL AFTER payment_method,
    ADD COLUMN manual_payment_comment VARCHAR(1000) NULL AFTER manual_paid_by,
    ADD COLUMN manual_payment_receipt_url VARCHAR(1024) NULL AFTER manual_payment_comment,
    ADD COLUMN manual_confirmed_at DATETIME(6) NULL AFTER manual_payment_receipt_url;

ALTER TABLE common_invoice_orders
    ADD COLUMN payment_method VARCHAR(32) NULL AFTER paid_at,
    ADD COLUMN manual_paid_by VARCHAR(160) NULL AFTER payment_method,
    ADD COLUMN manual_payment_comment VARCHAR(1000) NULL AFTER manual_paid_by,
    ADD COLUMN manual_payment_receipt_url VARCHAR(1024) NULL AFTER manual_payment_comment;

UPDATE business_audit_events
SET action = 'SETTLED_ORDER_STATUS_RESTORED',
    details = CONCAT(
        'Восстановлен корректный статус ранее оплаченного заказа. ',
        'Денежная операция не выполнялась. ',
        COALESCE(details, '')
    )
WHERE action = 'DUPLICATE_PAYMENT_CYCLE_REPAIRED';
