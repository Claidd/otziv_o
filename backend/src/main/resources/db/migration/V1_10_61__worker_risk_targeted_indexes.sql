SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_activity_events'
      AND index_name = 'idx_worker_activity_worker_action_review_created'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_activity_events ADD INDEX idx_worker_activity_worker_action_review_created (worker_user_id, action, review_id, created_at)',
              'SELECT ''idx_worker_activity_worker_action_review_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND index_name = 'idx_worker_risk_target_review'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD INDEX idx_worker_risk_target_review (worker_user_id, rule_code, status, review_id, created_at)',
              'SELECT ''idx_worker_risk_target_review exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND index_name = 'idx_worker_risk_target_order'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD INDEX idx_worker_risk_target_order (worker_user_id, rule_code, status, order_id, created_at)',
              'SELECT ''idx_worker_risk_target_order exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND index_name = 'idx_worker_risk_target_entity'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD INDEX idx_worker_risk_target_entity (worker_user_id, rule_code, status, entity_type, entity_id, created_at)',
              'SELECT ''idx_worker_risk_target_entity exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
