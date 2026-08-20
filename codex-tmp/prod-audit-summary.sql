SET NAMES utf8mb4;

SELECT 'now', NOW();

SELECT 'status_count', os.order_status_title, COUNT(*)
FROM orders o
JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE o.order_complete = b'0'
GROUP BY os.order_status_title
ORDER BY COUNT(*) DESC;

SELECT 'published_missing_invoice', COUNT(*)
FROM orders o
JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE o.order_complete = b'0'
  AND os.order_status_title = 'Опубликовано'
  AND NOT EXISTS (
    SELECT 1
    FROM scheduled_client_message_state s
    WHERE s.order_id = o.order_id
      AND s.scenario = 'PAYMENT_INVOICE_RETRY'
      AND s.state_status = 'ACTIVE'
      AND s.target_key = CONCAT('order:', o.order_id, ':', DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i:%s'))
  );

SELECT 'pay_status_missing_followup', os.order_status_title, COUNT(*)
FROM orders o
JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE o.order_complete = b'0'
  AND os.order_status_title IN ('Выставлен счет', 'Напоминание')
  AND NOT EXISTS (
    SELECT 1
    FROM scheduled_client_message_state s
    WHERE s.order_id = o.order_id
      AND s.scenario IN ('PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
      AND s.state_status = 'ACTIVE'
      AND s.target_key = CONCAT('order:', o.order_id, ':', DATE_FORMAT(o.order_status_changed_at, '%Y-%m-%dT%H:%i:%s'))
  )
GROUP BY os.order_status_title;

SELECT 'paid_active_payment_states', s.scenario, COUNT(*)
FROM scheduled_client_message_state s
JOIN orders o ON o.order_id = s.order_id
JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE s.state_status = 'ACTIVE'
  AND s.scenario IN ('PAYMENT_INVOICE_RETRY', 'PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION')
  AND os.order_status_title = 'Оплачено'
GROUP BY s.scenario;

SELECT 'due_active', s.scenario, os.order_status_title, COUNT(*)
FROM scheduled_client_message_state s
LEFT JOIN orders o ON o.order_id = s.order_id
LEFT JOIN order_statuses os ON os.order_status_id = o.order_status
WHERE s.state_status = 'ACTIVE'
  AND s.next_attempt_at IS NOT NULL
  AND s.next_attempt_at <= NOW()
  AND (s.locked_until IS NULL OR s.locked_until < NOW())
GROUP BY s.scenario, os.order_status_title
ORDER BY s.scenario, os.order_status_title;
