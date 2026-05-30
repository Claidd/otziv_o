CREATE TABLE IF NOT EXISTS business_audit_events (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    actor VARCHAR(150) NOT NULL,
    source VARCHAR(80) NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(80) NULL,
    order_id BIGINT NULL,
    review_id BIGINT NULL,
    old_value TEXT NULL,
    new_value TEXT NULL,
    details TEXT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_business_audit_order_created (order_id, created_at),
    INDEX idx_business_audit_review_created (review_id, created_at),
    INDEX idx_business_audit_action_created (action, created_at),
    INDEX idx_business_audit_actor_created (actor, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
