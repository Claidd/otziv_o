-- Older attachments recorded readiness without moving the order on the board.
-- Keep the original status snapshot used when detaching the order.
UPDATE orders o
JOIN common_invoice_orders item ON item.order_id = o.order_id
JOIN common_invoices invoice ON invoice.invoice_id = item.invoice_id
JOIN order_statuses current_status ON current_status.order_status_id = o.order_status
JOIN order_statuses waiting ON waiting.order_status_title = 'Ожидает общего счета'
SET o.order_status = waiting.order_status_id,
    o.order_status_changed_at = CURRENT_TIMESTAMP(6),
    o.row_version = o.row_version + 1
WHERE invoice.status = 'COLLECTING' AND invoice.invoice_purpose = 'STANDARD'
  AND item.active_membership AND item.ready AND NOT item.paid AND NOT item.unpaid
  AND NOT item.ordinary_after_paper_cancel AND NOT o.order_complete
  AND current_status.order_status_title IN ('Опубликовано', 'Выставлен счет', 'Напоминание');
