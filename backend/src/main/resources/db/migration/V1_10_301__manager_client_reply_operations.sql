-- Immutable operation identities outlive the daily control card. A single unresolved reply blocks
-- all cards for the same unanswered item, including cards created on another day.
CREATE TABLE manager_client_reply_operations (
    operation_token CHAR(36) NOT NULL,
    unanswered_item_id BIGINT NOT NULL,
    concrete_item_id BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    delivery_snapshot JSON NOT NULL,
    state ENUM('PREPARED', 'UNKNOWN', 'SUCCEEDED', 'FAILED_KNOWN') NOT NULL,
    prepared_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    result_channel VARCHAR(32) NULL,
    last_error_code VARCHAR(100) NULL,
    provider_message_id VARCHAR(512) NULL,
    source_applied BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_by_user_id BIGINT NULL,
    resolution_reason VARCHAR(1000) NULL,
    blocking_item_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN state IN ('PREPARED', 'UNKNOWN') THEN unanswered_item_id ELSE NULL END
    ) STORED,
    PRIMARY KEY (operation_token),
    UNIQUE KEY uk_manager_reply_request (unanswered_item_id, request_hash),
    UNIQUE KEY uk_manager_reply_unresolved (blocking_item_id),
    KEY idx_manager_reply_card (concrete_item_id, prepared_at)
) ENGINE=InnoDB;
