CREATE TABLE manual_payment_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    manager_id BIGINT NOT NULL,
    payment_profile_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    manual_phone VARCHAR(32) NOT NULL,
    manual_recipient_name VARCHAR(160) NOT NULL,
    target_amount_kopecks BIGINT NOT NULL,
    comment VARCHAR(255) NULL,
    created_by VARCHAR(160) NULL,
    updated_by VARCHAR(160) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_manual_payment_tasks_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_manual_payment_tasks_profile
        FOREIGN KEY (payment_profile_id) REFERENCES payment_profiles (id),
    INDEX idx_manual_payment_tasks_manager (manager_id),
    INDEX idx_manual_payment_tasks_profile_status (payment_profile_id, status),
    INDEX idx_manual_payment_tasks_status (status)
);

ALTER TABLE payment_links
    ADD COLUMN manual_source VARCHAR(32) NULL,
    ADD COLUMN manual_task_id BIGINT NULL,
    ADD CONSTRAINT fk_payment_links_manual_task
        FOREIGN KEY (manual_task_id) REFERENCES manual_payment_tasks (id),
    ADD INDEX idx_payment_links_manual_task (manual_task_id),
    ADD INDEX idx_payment_links_manual_source (manual_source);

UPDATE payment_links
SET manual_source = 'PROFILE_MONTHLY_LIMIT'
WHERE payment_method = 'MANUAL_MOBILE_BANK'
  AND manual_source IS NULL;
