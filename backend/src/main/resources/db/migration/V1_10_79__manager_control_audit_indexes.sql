SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'manager_daily_control_concrete_items'
      AND index_name = 'idx_manager_control_worker_explanation_pending'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE manager_daily_control_concrete_items ADD INDEX idx_manager_control_worker_explanation_pending (worker_notification_accepted_by_user_id, worker_explanation_at, worker_explanation_requested_at, worker_explanation_prompted_at)',
              'SELECT ''idx_manager_control_worker_explanation_pending exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'manager_daily_control_concrete_items'
      AND index_name = 'idx_manager_control_concrete_entity'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE manager_daily_control_concrete_items ADD INDEX idx_manager_control_concrete_entity (entity_type, entity_id, control_id, item_status)',
              'SELECT ''idx_manager_control_concrete_entity exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
