DELETE reminder
FROM personal_reminders reminder
JOIN orders `order` ON `order`.order_id = reminder.source_order_id
JOIN order_statuses status ON status.order_status_id = `order`.order_status
WHERE reminder.source_type = 'BAD_REVIEW_ORDER_READY'
  AND status.order_status_title IN ('Оплачено', 'Бан');
