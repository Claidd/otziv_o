-- Fully paid common invoices are closed groups and must use the same retention
-- lifecycle as manually archived and banned groups.
UPDATE common_invoices
SET closed_at = COALESCE(closed_at, paid_at, updated_at, created_at),
    closed_by = COALESCE(NULLIF(closed_by, ''), NULLIF(manual_paid_by, ''), 'migration:paid-backfill'),
    close_reason = COALESCE(NULLIF(close_reason, ''), 'PAID')
WHERE status = 'PAID'
  AND closed_at IS NULL;

-- Before grouped archive support existed, individual orders could be archived
-- while their parent common invoice remained COLLECTING. Reconcile only groups
-- whose every child is already in the ordinary Архив status.
CREATE TEMPORARY TABLE legacy_common_invoice_archive_candidates (
    invoice_id BIGINT NOT NULL,
    inferred_closed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (invoice_id)
) ENGINE = MEMORY;

INSERT INTO legacy_common_invoice_archive_candidates (invoice_id, inferred_closed_at)
SELECT
    ci.invoice_id,
    COALESCE(
        MAX(o.order_status_changed_at),
        MAX(CAST(o.order_changed AS DATETIME)),
        MAX(CAST(o.order_created AS DATETIME)),
        ci.updated_at,
        ci.created_at,
        CURRENT_TIMESTAMP(6)
    )
FROM common_invoices ci
JOIN common_invoice_orders cio ON cio.invoice_id = ci.invoice_id
JOIN orders o ON o.order_id = cio.order_id
JOIN order_statuses status ON status.order_status_id = o.order_status
WHERE ci.status = 'COLLECTING'
GROUP BY ci.invoice_id, ci.updated_at, ci.created_at
HAVING SUM(status.order_status_title <> 'Архив') = 0;

UPDATE common_invoice_orders cio
JOIN legacy_common_invoice_archive_candidates candidate
  ON candidate.invoice_id = cio.invoice_id
SET cio.archive_source_order_status_title = CASE
        WHEN cio.original_order_status_title IN ('В проверку', 'На проверке', 'Коррекция')
            THEN cio.original_order_status_title
        ELSE 'В проверку'
    END
WHERE cio.archive_source_order_status_title IS NULL
   OR cio.archive_source_order_status_title = '';

UPDATE common_invoices ci
JOIN legacy_common_invoice_archive_candidates candidate
  ON candidate.invoice_id = ci.invoice_id
SET ci.previous_status = 'COLLECTING',
    ci.status = 'ARCHIVED',
    ci.closed_at = candidate.inferred_closed_at,
    ci.closed_by = 'migration:legacy-reconcile',
    ci.close_reason = 'LEGACY_ARCHIVE',
    ci.next_reminder_at = NULL,
    ci.last_error = NULL,
    ci.updated_at = CURRENT_TIMESTAMP(6);

DROP TEMPORARY TABLE legacy_common_invoice_archive_candidates;

-- Empty collecting invoices have no recoverable group content and otherwise
-- remain visible forever. Disable only stale rows; recent empty rows may still
-- be in the process of receiving their first order.
UPDATE common_invoices ci
SET ci.previous_status = 'COLLECTING',
    ci.status = 'DISABLED',
    ci.closed_at = COALESCE(ci.closed_at, ci.updated_at, ci.created_at, CURRENT_TIMESTAMP(6)),
    ci.closed_by = COALESCE(NULLIF(ci.closed_by, ''), 'migration:empty-disable'),
    ci.close_reason = COALESCE(NULLIF(ci.close_reason, ''), 'EMPTY_DISABLED'),
    ci.next_reminder_at = NULL,
    ci.last_error = 'empty: в общем счете нет заказов',
    ci.updated_at = CURRENT_TIMESTAMP(6)
WHERE ci.status = 'COLLECTING'
  AND ci.updated_at < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY)
  AND NOT EXISTS (
        SELECT 1
        FROM common_invoice_orders cio
        WHERE cio.invoice_id = ci.invoice_id
  );
