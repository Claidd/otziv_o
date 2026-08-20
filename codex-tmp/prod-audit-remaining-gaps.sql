SET NAMES utf8mb4;

SELECT
  'published_missing',
  o.order_id,
  c.company_title,
  os.order_status_title,
  o.order_status_changed_at,
  EXISTS (
    SELECT 1
    FROM common_invoice_orders cio
    JOIN common_invoices ci ON ci.invoice_id = cio.invoice_id
    WHERE cio.order_id = o.order_id
      AND cio.active_membership = 1
      AND ci.status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'NEEDS_ATTENTION')
  ) AS active_common_invoice,
  COALESCE(GROUP_CONCAT(CONCAT(s.scenario, ':', s.state_status, ':', s.target_key, ':sent=', s.sent_count) ORDER BY s.state_id SEPARATOR ' | '), 'NO_STATE') AS states
FROM orders o
JOIN companies c ON c.company_id = o.order_company
JOIN order_statuses os ON os.order_status_id = o.order_status
LEFT JOIN scheduled_client_message_state s
  ON s.order_id = o.order_id
 AND s.scenario IN ('PAYMENT_INVOICE_RETRY', 'PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
WHERE o.order_complete = b'0'
  AND os.order_status_title = 'Опубликовано'
  AND NOT EXISTS (
    SELECT 1
    FROM scheduled_client_message_state active_state
    WHERE active_state.order_id = o.order_id
      AND active_state.scenario = 'PAYMENT_INVOICE_RETRY'
      AND active_state.state_status = 'ACTIVE'
      AND active_state.target_key = CONCAT(
        'order:',
        o.order_id,
        ':',
        CASE
          WHEN SECOND(o.order_status_changed_at) = 0
            THEN DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i')
          ELSE DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i:%s')
        END
      )
  )
GROUP BY o.order_id, c.company_title, os.order_status_title, o.order_status_changed_at
ORDER BY o.order_id;

SELECT
  'followup_missing',
  gap.order_status_title,
  SUM(CASE WHEN active_common_invoice THEN 1 ELSE 0 END) AS common_invoice,
  SUM(CASE WHEN active_common_invoice THEN 0 ELSE 1 END) AS regular_orders,
  COUNT(*) AS total
FROM (
  SELECT
    o.order_id,
    os.order_status_title,
    EXISTS (
      SELECT 1
      FROM common_invoice_orders cio
      JOIN common_invoices ci ON ci.invoice_id = cio.invoice_id
      WHERE cio.order_id = o.order_id
        AND cio.active_membership = 1
        AND ci.status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'NEEDS_ATTENTION')
    ) AS active_common_invoice
  FROM orders o
  JOIN order_statuses os ON os.order_status_id = o.order_status
  WHERE o.order_complete = b'0'
    AND os.order_status_title IN ('Выставлен счет', 'Напоминание')
    AND NOT EXISTS (
      SELECT 1
      FROM scheduled_client_message_state active_state
      WHERE active_state.order_id = o.order_id
        AND active_state.scenario IN ('PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
        AND active_state.state_status = 'ACTIVE'
        AND active_state.target_key = CONCAT(
          'order:',
          o.order_id,
          ':',
          CASE
            WHEN SECOND(o.order_status_changed_at) = 0
              THEN DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i')
            ELSE DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i:%s')
          END
        )
    )
) gap
GROUP BY gap.order_status_title;
