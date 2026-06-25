INSERT INTO order_statuses (order_status_title)
SELECT 'Опубликовано'
WHERE NOT EXISTS (
    SELECT 1 FROM order_statuses WHERE order_status_title = 'Опубликовано'
);

CREATE TABLE IF NOT EXISTS common_billing_disabled_account_cleanup (
    cleanup_id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    restored_status_title VARCHAR(64) NULL,
    detected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (cleanup_id),
    UNIQUE KEY uk_common_billing_disabled_cleanup_order (invoice_id, order_id),
    INDEX idx_common_billing_disabled_cleanup_account (account_id, detected_at)
);

INSERT IGNORE INTO common_billing_disabled_account_cleanup (
    invoice_id,
    account_id,
    order_id,
    restored_status_title
)
SELECT
    invoice.invoice_id,
    account.account_id,
    item.order_id,
    CASE
        WHEN item.original_order_status_title IS NULL
            OR item.original_order_status_title = ''
            OR item.original_order_status_title = 'Ожидает общего счета'
            THEN 'Опубликовано'
        ELSE item.original_order_status_title
    END
FROM common_invoices invoice
JOIN common_billing_accounts account ON account.account_id = invoice.account_id
JOIN common_invoice_orders item ON item.invoice_id = invoice.invoice_id
WHERE account.enabled = FALSE
  AND invoice.status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID')
  AND item.paid = FALSE;

UPDATE orders order_row
JOIN common_invoice_orders item ON item.order_id = order_row.order_id
JOIN common_invoices invoice ON invoice.invoice_id = item.invoice_id
JOIN common_billing_accounts account ON account.account_id = invoice.account_id
LEFT JOIN order_statuses target_status
    ON target_status.order_status_title = CASE
        WHEN item.original_order_status_title IS NULL
            OR item.original_order_status_title = ''
            OR item.original_order_status_title = 'Ожидает общего счета'
            THEN 'Опубликовано'
        ELSE item.original_order_status_title
    END
LEFT JOIN order_statuses published_status ON published_status.order_status_title = 'Опубликовано'
SET order_row.order_status = COALESCE(target_status.order_status_id, published_status.order_status_id),
    order_row.order_status_changed_at = CURRENT_TIMESTAMP(6)
WHERE account.enabled = FALSE
  AND invoice.status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID')
  AND item.paid = FALSE;

DELETE item
FROM common_invoice_orders item
JOIN common_invoices invoice ON invoice.invoice_id = item.invoice_id
JOIN common_billing_accounts account ON account.account_id = invoice.account_id
WHERE account.enabled = FALSE
  AND invoice.status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID')
  AND item.paid = FALSE;

UPDATE common_invoices invoice
JOIN common_billing_accounts account ON account.account_id = invoice.account_id
SET invoice.status = 'DISABLED',
    invoice.next_reminder_at = NULL,
    invoice.last_error = 'disabled: общий счет выключен, неоплаченные заказы отключены',
    invoice.updated_at = CURRENT_TIMESTAMP(6)
WHERE account.enabled = FALSE
  AND invoice.status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID');
