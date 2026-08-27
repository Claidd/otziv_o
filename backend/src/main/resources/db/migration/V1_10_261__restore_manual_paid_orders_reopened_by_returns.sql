-- Historical provider returns must not reopen an order when a newer manual
-- paid closure already superseded that returned link. Restore orders that were
-- reopened by that legacy path and close the duplicate payment automation.

UPDATE orders order_row
JOIN order_statuses reminder_status
  ON reminder_status.order_status_id = order_row.order_status
 AND reminder_status.order_status_title = 'Напоминание'
JOIN order_statuses paid_status
  ON paid_status.order_status_title = 'Оплачено'
JOIN (
    SELECT candidate_order_id AS order_id,
           DATE(MAX(manual_paid_closed_at)) AS manual_paid_date,
           DATE(MAX(reopened_at)) AS reopened_date
    FROM (
        SELECT returned_link.order_id AS candidate_order_id,
               manual_closed.updated_at AS manual_paid_closed_at,
               reopen_event.created_at AS reopened_at
        FROM payment_link_return_reconciliation_outbox return_outbox
        JOIN payment_links returned_link
          ON returned_link.id = return_outbox.payment_link_id
        JOIN payment_links manual_closed
          ON manual_closed.order_id = returned_link.order_id
         AND manual_closed.status = 'CANCELED'
         AND manual_closed.last_error LIKE '%оплаченным вручную%'
         AND manual_closed.updated_at > COALESCE(
              returned_link.paid_at,
              returned_link.manual_confirmed_at,
              returned_link.updated_at,
              returned_link.created_at
         )
        JOIN business_audit_events reopen_event
          ON reopen_event.order_id = returned_link.order_id
         AND reopen_event.action = 'order_status_changed'
         AND reopen_event.old_value = 'Оплачено'
         AND reopen_event.new_value = 'Напоминание'
        WHERE return_outbox.status = 'SUCCEEDED'
          AND return_outbox.observed_status IN ('REFUNDED', 'REVERSED', 'CANCELED')
    ) candidate_source
    GROUP BY candidate_order_id
) repaired_orders
  ON repaired_orders.order_id = order_row.order_id
SET order_row.order_status = paid_status.order_status_id,
    order_row.order_complete = 1,
    order_row.order_pay_day = COALESCE(
        order_row.order_pay_day,
        repaired_orders.manual_paid_date,
        repaired_orders.reopened_date
    ),
    order_row.order_waiting_for_client = 0,
    order_row.order_waiting_for_client_changed_at = NULL,
    order_row.order_client_text_expected = 0,
    order_row.order_status_changed_at = CURRENT_TIMESTAMP(6),
    order_row.row_version = order_row.row_version + 1;

UPDATE payment_links link
JOIN (
    SELECT order_id
    FROM (
        SELECT DISTINCT returned_link.order_id
        FROM payment_link_return_reconciliation_outbox return_outbox
        JOIN payment_links returned_link
          ON returned_link.id = return_outbox.payment_link_id
        JOIN payment_links manual_closed
          ON manual_closed.order_id = returned_link.order_id
         AND manual_closed.status = 'CANCELED'
         AND manual_closed.last_error LIKE '%оплаченным вручную%'
         AND manual_closed.updated_at > COALESCE(
              returned_link.paid_at,
              returned_link.manual_confirmed_at,
              returned_link.updated_at,
              returned_link.created_at
         )
        JOIN business_audit_events reopen_event
          ON reopen_event.order_id = returned_link.order_id
         AND reopen_event.action = 'order_status_changed'
         AND reopen_event.old_value = 'Оплачено'
         AND reopen_event.new_value = 'Напоминание'
        WHERE return_outbox.status = 'SUCCEEDED'
          AND return_outbox.observed_status IN ('REFUNDED', 'REVERSED', 'CANCELED')
    ) candidate_source
) repaired_orders
  ON repaired_orders.order_id = link.order_id
JOIN orders order_row ON order_row.order_id = link.order_id
JOIN order_statuses paid_status
  ON paid_status.order_status_id = order_row.order_status
 AND paid_status.order_status_title = 'Оплачено'
SET link.status = 'EXPIRED',
    link.expires_at = CASE
        WHEN link.expires_at IS NULL THEN CURRENT_TIMESTAMP(6)
        WHEN link.expires_at > CURRENT_TIMESTAMP(6) THEN CURRENT_TIMESTAMP(6)
        ELSE link.expires_at
    END,
    link.last_error = 'Ошибочная ссылка закрыта: заказ восстановлен как вручную оплаченный после старого возврата',
    link.updated_at = CURRENT_TIMESTAMP(6),
    link.row_version = link.row_version + 1
WHERE link.status = 'CREATED'
  AND link.payment_url IS NULL
  AND link.tbank_payment_id IS NULL;

UPDATE scheduled_client_message_state state
JOIN (
    SELECT DISTINCT returned_link.order_id
    FROM payment_link_return_reconciliation_outbox return_outbox
    JOIN payment_links returned_link
      ON returned_link.id = return_outbox.payment_link_id
    JOIN payment_links manual_closed
      ON manual_closed.order_id = returned_link.order_id
     AND manual_closed.status = 'CANCELED'
     AND manual_closed.last_error LIKE '%оплаченным вручную%'
     AND manual_closed.updated_at > COALESCE(
          returned_link.paid_at,
          returned_link.manual_confirmed_at,
          returned_link.updated_at,
          returned_link.created_at
     )
    JOIN business_audit_events reopen_event
      ON reopen_event.order_id = returned_link.order_id
     AND reopen_event.action = 'order_status_changed'
     AND reopen_event.old_value = 'Оплачено'
     AND reopen_event.new_value = 'Напоминание'
    WHERE return_outbox.status = 'SUCCEEDED'
      AND return_outbox.observed_status IN ('REFUNDED', 'REVERSED', 'CANCELED')
) repaired_orders
  ON repaired_orders.order_id = state.order_id
JOIN orders order_row ON order_row.order_id = state.order_id
JOIN order_statuses paid_status
  ON paid_status.order_status_id = order_row.order_status
 AND paid_status.order_status_title = 'Оплачено'
SET state.state_status = 'DONE',
    state.next_attempt_at = NULL,
    state.locked_until = NULL,
    state.last_error_code = 'manual_paid_reopen_repaired',
    state.last_error_message = 'Оплатное напоминание закрыто: заказ восстановлен как вручную оплаченный после старого возврата',
    state.updated_at = CURRENT_TIMESTAMP(6)
WHERE state.state_status = 'ACTIVE'
  AND state.scenario IN ('PAYMENT_INVOICE_RETRY', 'PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION');
