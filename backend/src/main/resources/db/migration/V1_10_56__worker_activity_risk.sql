CREATE TABLE IF NOT EXISTS worker_activity_events (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    worker_user_id BIGINT NOT NULL,
    worker_username VARCHAR(150) NOT NULL,
    worker_name VARCHAR(200) NULL,
    action VARCHAR(60) NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    entity_id BIGINT NULL,
    order_id BIGINT NULL,
    review_id BIGINT NULL,
    section VARCHAR(40) NULL,
    details TEXT NULL,
    risk_score INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id),
    INDEX idx_worker_activity_worker_created (worker_user_id, created_at),
    INDEX idx_worker_activity_action_created (action, created_at),
    INDEX idx_worker_activity_review_created (review_id, created_at),
    INDEX idx_worker_activity_entity_created (entity_type, entity_id, created_at)
);

CREATE TABLE IF NOT EXISTS worker_risk_incidents (
    incident_id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    level VARCHAR(30) NOT NULL,
    rule_code VARCHAR(80) NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    worker_user_id BIGINT NOT NULL,
    worker_username VARCHAR(150) NOT NULL,
    worker_name VARCHAR(200) NULL,
    activity_event_id BIGINT NULL,
    action VARCHAR(60) NULL,
    entity_type VARCHAR(60) NULL,
    entity_id BIGINT NULL,
    order_id BIGINT NULL,
    review_id BIGINT NULL,
    title VARCHAR(180) NOT NULL,
    message TEXT NULL,
    details TEXT NULL,
    PRIMARY KEY (incident_id),
    INDEX idx_worker_risk_worker_status_created (worker_user_id, status, created_at),
    INDEX idx_worker_risk_rule_created (rule_code, created_at),
    INDEX idx_worker_risk_event (activity_event_id)
);

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_activity_events'
      AND index_name = 'idx_worker_activity_worker_created'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_activity_events ADD INDEX idx_worker_activity_worker_created (worker_user_id, created_at)',
              'SELECT ''idx_worker_activity_worker_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_activity_events'
      AND index_name = 'idx_worker_activity_action_created'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_activity_events ADD INDEX idx_worker_activity_action_created (action, created_at)',
              'SELECT ''idx_worker_activity_action_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_activity_events'
      AND index_name = 'idx_worker_activity_review_created'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_activity_events ADD INDEX idx_worker_activity_review_created (review_id, created_at)',
              'SELECT ''idx_worker_activity_review_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_activity_events'
      AND index_name = 'idx_worker_activity_entity_created'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_activity_events ADD INDEX idx_worker_activity_entity_created (entity_type, entity_id, created_at)',
              'SELECT ''idx_worker_activity_entity_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND index_name = 'idx_worker_risk_worker_status_created'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD INDEX idx_worker_risk_worker_status_created (worker_user_id, status, created_at)',
              'SELECT ''idx_worker_risk_worker_status_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND index_name = 'idx_worker_risk_rule_created'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD INDEX idx_worker_risk_rule_created (rule_code, created_at)',
              'SELECT ''idx_worker_risk_rule_created exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'worker_risk_incidents'
      AND index_name = 'idx_worker_risk_event'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE worker_risk_incidents ADD INDEX idx_worker_risk_event (activity_event_id)',
              'SELECT ''idx_worker_risk_event exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
