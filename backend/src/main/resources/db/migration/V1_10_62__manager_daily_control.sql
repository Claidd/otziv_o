CREATE TABLE IF NOT EXISTS manager_daily_controls (
    daily_control_id BIGINT NOT NULL AUTO_INCREMENT,
    control_date DATE NOT NULL,
    manager_id BIGINT NOT NULL,
    manager_user_id BIGINT NULL,
    control_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    started_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    last_activity_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (daily_control_id),
    UNIQUE KEY uk_manager_daily_control_day (control_date, manager_id),
    INDEX idx_manager_daily_control_manager (manager_id, control_date),
    INDEX idx_manager_daily_control_status (control_date, control_status)
);

CREATE TABLE IF NOT EXISTS manager_daily_control_items (
    control_item_id BIGINT NOT NULL AUTO_INCREMENT,
    control_id BIGINT NOT NULL,
    item_key VARCHAR(160) NOT NULL,
    item_type VARCHAR(40) NOT NULL,
    entity_id BIGINT NULL,
    worker_id BIGINT NULL,
    section_code VARCHAR(80) NULL,
    reason_code VARCHAR(80) NOT NULL,
    label VARCHAR(160) NOT NULL,
    target_url VARCHAR(500) NULL,
    item_count BIGINT NOT NULL DEFAULT 0,
    severity VARCHAR(30) NOT NULL,
    group_code VARCHAR(30) NOT NULL,
    item_status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    action_type VARCHAR(40) NULL,
    comment VARCHAR(1000) NULL,
    created_reminder_id BIGINT NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (control_item_id),
    UNIQUE KEY uk_manager_control_item_key (control_id, item_key),
    INDEX idx_manager_control_items_status (control_id, item_status, severity),
    INDEX idx_manager_control_items_type (item_type, reason_code, section_code)
);

CREATE TABLE IF NOT EXISTS manager_daily_control_events (
    control_event_id BIGINT NOT NULL AUTO_INCREMENT,
    control_id BIGINT NOT NULL,
    item_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    event_type VARCHAR(50) NOT NULL,
    action_type VARCHAR(40) NULL,
    comment VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (control_event_id),
    INDEX idx_manager_control_events_control (control_id, created_at),
    INDEX idx_manager_control_events_item (item_id, created_at)
);
