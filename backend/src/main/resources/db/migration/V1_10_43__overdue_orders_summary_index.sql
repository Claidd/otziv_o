SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_orders_complete_changed_status'
);

SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_complete_changed_status (order_complete, order_changed, order_status, order_id)',
    'SELECT ''idx_orders_complete_changed_status exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ANALYZE TABLE orders;
