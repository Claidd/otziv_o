SET NAMES utf8mb4;

SELECT 'now', NOW();

SELECT
  'indexes',
  INDEX_NAME,
  GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
FROM INFORMATION_SCHEMA.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'scheduled_client_message_state'
  AND INDEX_NAME IN ('uk_scheduled_message_scenario_target', 'idx_scheduled_message_state_order')
GROUP BY INDEX_NAME;

SELECT
  'orders',
  o.order_id,
  c.company_title,
  os.order_status_title,
  o.order_status_changed_at,
  o.order_complete,
  COALESCE(
    GROUP_CONCAT(
      CONCAT(
        s.state_id, ':', s.scenario, ':', s.state_status,
        ':next=', COALESCE(s.next_attempt_at, 'NULL'),
        ':sent=', s.sent_count,
        ':succ=', COALESCE(s.last_success_at, 'NULL'),
        ':err=', COALESCE(s.last_error_code, 'NULL'),
        ':key=', s.target_key
      )
      ORDER BY s.state_id
      SEPARATOR ' || '
    ),
    'NO_STATE'
  ) AS states
FROM orders o
JOIN companies c ON c.company_id = o.order_company
JOIN order_statuses os ON os.order_status_id = o.order_status
LEFT JOIN scheduled_client_message_state s
  ON s.order_id = o.order_id
 AND s.scenario IN ('PAYMENT_INVOICE_RETRY', 'PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
WHERE o.order_id IN (24888, 23140, 24175)
GROUP BY o.order_id, c.company_title, os.order_status_title, o.order_status_changed_at, o.order_complete
ORDER BY o.order_id;

SELECT
  'links',
  p.order_id,
  p.id,
  p.status,
  p.payment_method,
  p.amount_kopecks,
  p.created_at,
  p.updated_at,
  p.expires_at,
  COALESCE(p.last_error, '')
FROM payment_links p
WHERE p.order_id IN (24888, 23140, 24175)
ORDER BY p.order_id, p.id;
