-- A verified T-Bank cancel webhook can race with manual attribution of the
-- same common invoice. Older code archived the canceled provider reference,
-- but could leave the invoice in NEEDS_ATTENTION after every item and the
-- immutable ACTUAL_PAYMENT receipt had already been confirmed. Repair only
-- that fully evidenced terminal tail; no cash, salary or allocation amount is
-- synthesized or rewritten.

CREATE TEMPORARY TABLE v270_completed_manual_invoice_tail AS
SELECT invoice.invoice_id,
       MIN(item.paid_at) AS paid_at,
       MAX(NULLIF(item.manual_paid_by, '')) AS manual_paid_by,
       MAX(NULLIF(item.manual_payment_comment, '')) AS manual_payment_comment,
       MAX(NULLIF(item.manual_payment_receipt_url, '')) AS manual_payment_receipt_url
FROM common_invoices invoice
JOIN common_invoice_orders item
  ON item.invoice_id = invoice.invoice_id
 AND item.active_membership = 1
WHERE invoice.status = 'NEEDS_ATTENTION'
  AND invoice.last_error LIKE 'tbank_payment_terminal:%'
  AND invoice.amount_kopecks > 0
  AND invoice.paid_kopecks >= invoice.amount_kopecks
  AND invoice.tbank_order_id IS NULL
  AND invoice.tbank_payment_id IS NULL
  AND invoice.tbank_terminal_key IS NULL
  AND invoice.tbank_payment_amount_kopecks IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoice_orders open_item
      WHERE open_item.invoice_id = invoice.invoice_id
        AND open_item.active_membership = 1
        AND (open_item.paid = 0 OR open_item.unpaid = 1)
  )
  AND NOT EXISTS (
      SELECT 1
      FROM common_invoice_payment_refs payment_ref
      WHERE payment_ref.invoice_id = invoice.invoice_id
        AND payment_ref.status NOT IN ('CANCELED', 'REJECTED', 'DEADLINE_EXPIRED')
  )
  AND EXISTS (
      SELECT 1
      FROM contractor_payment_allocations allocation
      WHERE allocation.common_invoice_id = invoice.invoice_id
        AND allocation.mode = 'LIVE'
        AND allocation.source_type = 'ACTUAL_PAYMENT'
        AND allocation.status IN ('CONFIRMED', 'PARTIALLY_RETURNED', 'RETURNED')
      GROUP BY allocation.common_invoice_id
      HAVING SUM(GREATEST(0, allocation.confirmed_kopecks - allocation.returned_kopecks))
             >= invoice.amount_kopecks
  )
GROUP BY invoice.invoice_id;

INSERT INTO business_audit_events (
    created_at, actor, source, action, entity_type, entity_id,
    order_id, old_value, new_value, details
)
SELECT CURRENT_TIMESTAMP(6),
       'system:flyway-v270',
       'common_billing_repair',
       'COMPLETED_MANUAL_COMMON_INVOICE_TERMINAL_TAIL_RESOLVED',
       'COMMON_INVOICE',
       CAST(repair.invoice_id AS CHAR),
       NULL,
       'status=NEEDS_ATTENTION;error=tbank_payment_terminal',
       'status=PAID;error=NULL',
       'Все позиции оплачены, фактический получатель подтвержден LIVE ACTUAL_PAYMENT, старая T-Bank ссылка имеет безопасный конечный статус'
FROM v270_completed_manual_invoice_tail repair;

UPDATE common_invoices invoice
JOIN v270_completed_manual_invoice_tail repair
  ON repair.invoice_id = invoice.invoice_id
SET invoice.status = 'PAID',
    invoice.previous_status = COALESCE(invoice.previous_status, 'NEEDS_ATTENTION'),
    invoice.payment_method = COALESCE(NULLIF(invoice.payment_method, ''), 'MANUAL'),
    invoice.manual_paid_by = COALESCE(NULLIF(invoice.manual_paid_by, ''), repair.manual_paid_by),
    invoice.manual_payment_comment = COALESCE(
        NULLIF(invoice.manual_payment_comment, ''),
        repair.manual_payment_comment
    ),
    invoice.manual_payment_receipt_url = COALESCE(
        NULLIF(invoice.manual_payment_receipt_url, ''),
        repair.manual_payment_receipt_url
    ),
    invoice.manual_confirmed_at = COALESCE(invoice.manual_confirmed_at, repair.paid_at, CURRENT_TIMESTAMP(6)),
    invoice.paid_at = COALESCE(invoice.paid_at, repair.paid_at, CURRENT_TIMESTAMP(6)),
    invoice.closed_at = COALESCE(invoice.closed_at, repair.paid_at, CURRENT_TIMESTAMP(6)),
    invoice.closed_by = COALESCE(NULLIF(invoice.closed_by, ''), repair.manual_paid_by, 'payment-confirmation'),
    invoice.close_reason = 'PAID',
    invoice.next_reminder_at = NULL,
    invoice.last_error = NULL,
    invoice.updated_at = CURRENT_TIMESTAMP(6);

DROP TEMPORARY TABLE v270_completed_manual_invoice_tail;
