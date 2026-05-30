SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_orders_client_text_waiting_complete'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_client_text_waiting_complete (order_waiting_for_client, order_complete, order_status, order_waiting_for_client_changed_at, order_status_changed_at, order_id)',
    'SELECT ''idx_orders_client_text_waiting_complete exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'managers'
      AND INDEX_NAME = 'idx_managers_client_id_user'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE managers ADD INDEX idx_managers_client_id_user (client_id(191), user_id, manager_id)',
    'SELECT ''idx_managers_client_id_user exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ANALYZE TABLE orders, managers;
