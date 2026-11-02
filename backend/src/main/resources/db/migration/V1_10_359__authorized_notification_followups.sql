-- Owner-authorized duplicate risk: one separate informational follow-up per original operation.
-- No FK to live orders/payment links: delivery history survives financial archival.
CREATE TABLE client_notification_followups (
    original_operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    source_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_ciphertext MEDIUMTEXT NOT NULL,
    delivery_state VARCHAR(32) NOT NULL,
    provider_message_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error_code VARCHAR(100) NULL,
    policy_version VARCHAR(64) NOT NULL DEFAULT 'OWNER_APPROVED_ONE_FOLLOWUP_20261101',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    next_receipt_check_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
    owner_applied_at DATETIME(6) NULL,
    PRIMARY KEY (original_operation_id),
    UNIQUE KEY uk_notification_followup_operation (operation_id),
    KEY ix_notification_followup_receipt (delivery_state, next_receipt_check_at),
    KEY ix_notification_followup_started (started_at)
) ENGINE=InnoDB;

ALTER TABLE payment_links ADD COLUMN payment_success_notification_recovery_operation_id
    VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE archive_payment_links ADD COLUMN payment_success_notification_recovery_operation_id
    VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL;
