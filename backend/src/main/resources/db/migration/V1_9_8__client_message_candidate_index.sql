SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_orders_client_messages'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_client_messages (order_status, order_complete, order_status_changed_at, order_id)',
    'SELECT ''idx_orders_client_messages exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
