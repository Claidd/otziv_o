UPDATE orders
SET order_waiting_for_client_changed_at = COALESCE(
        order_waiting_for_client_changed_at,
        order_status_changed_at,
        TIMESTAMP(order_changed),
        order_created,
        CURRENT_TIMESTAMP(6)
    )
WHERE order_waiting_for_client = TRUE
  AND order_waiting_for_client_changed_at IS NULL;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_orders_client_text_waiting_direct'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_client_text_waiting_direct (order_waiting_for_client, order_complete, order_waiting_for_client_changed_at, order_id, order_status)',
    'SELECT ''idx_orders_client_text_waiting_direct exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ANALYZE TABLE orders, scheduled_client_message_state, reviews, leads, bots, payment_links, app_settings;
