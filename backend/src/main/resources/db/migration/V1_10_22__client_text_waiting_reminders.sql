ALTER TABLE orders
    ADD COLUMN order_waiting_for_client_changed_at DATETIME(6) NULL;

UPDATE orders
SET order_waiting_for_client_changed_at = CURRENT_TIMESTAMP(6)
WHERE order_waiting_for_client = TRUE
  AND order_waiting_for_client_changed_at IS NULL;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_orders_client_text_waiting_messages'
);
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_client_text_waiting_messages (order_waiting_for_client, order_status, order_waiting_for_client_changed_at, order_id)',
    'SELECT ''idx_orders_client_text_waiting_messages exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.messages.client-text-reminder.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('client.messages.client-text-reminder.interval-days', '3', CURRENT_TIMESTAMP(6)),
    ('client.messages.client-text-reminder.statuses', 'Новый', CURRENT_TIMESTAMP(6)),
    ('client.messages.client-text-reminder-text', '{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, пришлите текст или пожелания для отзывов по заказу №{orderId}, чтобы мы могли продолжить работу.', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
