CREATE TEMPORARY TABLE common_billing_orphan_backfill_accounts AS
SELECT DISTINCT account.account_id
FROM common_billing_accounts account
JOIN common_billing_account_companies link
    ON link.account_id = account.account_id
JOIN orders order_row
    ON order_row.order_company = link.company_id
JOIN order_statuses status
    ON status.order_status_id = order_row.order_status
LEFT JOIN common_invoice_orders existing_item
    ON existing_item.order_id = order_row.order_id
WHERE account.enabled = TRUE
  AND link.enabled = TRUE
  AND order_row.order_complete = FALSE
  AND existing_item.invoice_order_id IS NULL
  AND status.order_status_title IN (
      'Новый',
      'Нагул',
      'В проверку',
      'Коррекция',
      'На проверке',
      'Публикация',
      'Опубликовано',
      'Выставлен счет',
      'Напоминание',
      'Ожидает общего счета'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoices current_invoice
      WHERE current_invoice.account_id = account.account_id
        AND current_invoice.status IN ('COLLECTING', 'READY')
  );

INSERT INTO common_invoices (
    account_id,
    token,
    title,
    status
)
SELECT
    account.account_id,
    REPLACE(UUID(), '-', ''),
    CONCAT(account.account_name, ' - общий счет'),
    'COLLECTING'
FROM common_billing_accounts account
JOIN common_billing_orphan_backfill_accounts orphan_account
    ON orphan_account.account_id = account.account_id;

DROP TEMPORARY TABLE common_billing_orphan_backfill_accounts;

CREATE TEMPORARY TABLE common_billing_orphan_backfill_targets AS
SELECT
    order_row.order_id,
    (
        SELECT current_invoice.invoice_id
        FROM common_invoices current_invoice
        WHERE current_invoice.account_id = account.account_id
          AND current_invoice.status IN ('COLLECTING', 'READY')
        ORDER BY current_invoice.invoice_id DESC
        LIMIT 1
    ) AS invoice_id,
    status.order_status_title,
    CASE
        WHEN order_row.order_sum IS NOT NULL THEN CAST(ROUND(order_row.order_sum * 100) AS SIGNED)
        WHEN order_row.order_amount IS NOT NULL THEN order_row.order_amount * 100000
        ELSE 0
    END AS amount_kopecks,
    CASE
        WHEN status.order_status_title IN (
            'Опубликовано',
            'Выставлен счет',
            'Напоминание',
            'Ожидает общего счета'
        )
        THEN TRUE
        ELSE FALSE
    END AS ready
FROM common_billing_accounts account
JOIN common_billing_account_companies link
    ON link.account_id = account.account_id
JOIN orders order_row
    ON order_row.order_company = link.company_id
JOIN order_statuses status
    ON status.order_status_id = order_row.order_status
LEFT JOIN common_invoice_orders existing_item
    ON existing_item.order_id = order_row.order_id
WHERE account.enabled = TRUE
  AND link.enabled = TRUE
  AND order_row.order_complete = FALSE
  AND existing_item.invoice_order_id IS NULL
  AND status.order_status_title IN (
      'Новый',
      'Нагул',
      'В проверку',
      'Коррекция',
      'На проверке',
      'Публикация',
      'Опубликовано',
      'Выставлен счет',
      'Напоминание',
      'Ожидает общего счета'
  );

INSERT IGNORE INTO common_invoice_orders (
    invoice_id,
    order_id,
    amount_kopecks,
    original_order_status_title,
    ready
)
SELECT
    target.invoice_id,
    target.order_id,
    target.amount_kopecks,
    target.order_status_title,
    target.ready
FROM common_billing_orphan_backfill_targets target
WHERE target.invoice_id IS NOT NULL;

UPDATE common_invoices invoice
JOIN (
    SELECT
        item.invoice_id,
        SUM(item.amount_kopecks) AS amount_kopecks,
        SUM(CASE WHEN item.paid THEN item.amount_kopecks ELSE 0 END) AS paid_kopecks,
        COUNT(*) AS total_orders,
        SUM(CASE WHEN item.ready THEN 1 ELSE 0 END) AS ready_orders,
        SUM(CASE WHEN status.order_status_title IN (
            'Новый',
            'Нагул',
            'В проверку',
            'Коррекция',
            'На проверке',
            'Публикация'
        ) THEN 1 ELSE 0 END) AS active_work_orders
    FROM common_invoice_orders item
    JOIN orders order_row
        ON order_row.order_id = item.order_id
    LEFT JOIN order_statuses status
        ON status.order_status_id = order_row.order_status
    WHERE item.invoice_id IN (
        SELECT DISTINCT invoice_id
        FROM common_billing_orphan_backfill_targets
        WHERE invoice_id IS NOT NULL
    )
    GROUP BY item.invoice_id
) totals
    ON totals.invoice_id = invoice.invoice_id
SET invoice.amount_kopecks = totals.amount_kopecks,
    invoice.paid_kopecks = LEAST(totals.amount_kopecks, totals.paid_kopecks),
    invoice.status = CASE
        WHEN invoice.status = 'COLLECTING'
            AND totals.total_orders > 0
            AND totals.ready_orders = totals.total_orders
            AND totals.active_work_orders = 0
            THEN 'READY'
        WHEN invoice.status = 'READY'
            AND (
                totals.ready_orders <> totals.total_orders
                OR totals.active_work_orders > 0
            )
            THEN 'COLLECTING'
        ELSE invoice.status
    END,
    invoice.updated_at = CURRENT_TIMESTAMP(6);

UPDATE orders order_row
JOIN common_invoice_orders item
    ON item.order_id = order_row.order_id
JOIN common_invoices invoice
    ON invoice.invoice_id = item.invoice_id
JOIN order_statuses published_status
    ON published_status.order_status_title = 'Опубликовано'
LEFT JOIN order_statuses current_status
    ON current_status.order_status_id = order_row.order_status
SET order_row.order_status = published_status.order_status_id,
    order_row.order_status_changed_at = CURRENT_TIMESTAMP(6)
WHERE item.invoice_id IN (
    SELECT DISTINCT invoice_id
    FROM common_billing_orphan_backfill_targets
    WHERE invoice_id IS NOT NULL
)
  AND invoice.status = 'READY'
  AND COALESCE(current_status.order_status_title, '') <> 'Опубликовано';

DROP TEMPORARY TABLE common_billing_orphan_backfill_targets;
