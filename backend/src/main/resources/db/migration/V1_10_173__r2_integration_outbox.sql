-- Runtime code must keep credentials, bearer values, and other secrets out of
-- payload. The processing token is indexed but intentionally not unique so a
-- later bounded batch claim can share one fencing token.
CREATE TABLE integration_outbox (
    integration_outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    aggregate_version BIGINT UNSIGNED NULL,
    event_type VARCHAR(160) NOT NULL,
    deduplication_key_hash BINARY(32) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'PENDING',
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    max_attempts INT UNSIGNED NOT NULL DEFAULT 20,
    processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    processing_owner VARCHAR(128) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_lease_until DATETIME(6) NULL,
    last_error VARCHAR(2000) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (integration_outbox_id),
    UNIQUE KEY uk_integration_outbox_event_id (event_id),
    UNIQUE KEY uk_integration_outbox_dedup_hash (deduplication_key_hash),
    INDEX idx_integration_outbox_due
        (status, available_at, processing_lease_until, integration_outbox_id),
    INDEX idx_integration_outbox_processing_token
        (processing_token, integration_outbox_id),
    INDEX idx_integration_outbox_aggregate
        (aggregate_type, aggregate_id, aggregate_version, integration_outbox_id),
    CONSTRAINT ck_integration_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'DEAD')),
    CONSTRAINT ck_integration_outbox_attempts
        CHECK (max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_integration_outbox_processing_lease
        CHECK (
            (
                status = 'PROCESSING'
                AND processing_token IS NOT NULL
                AND processing_owner IS NOT NULL
                AND processing_started_at IS NOT NULL
                AND processing_lease_until IS NOT NULL
                AND processing_lease_until > processing_started_at
            )
            OR (
                status <> 'PROCESSING'
                AND processing_token IS NULL
                AND processing_owner IS NULL
                AND processing_started_at IS NULL
                AND processing_lease_until IS NULL
            )
        ),
    CONSTRAINT ck_integration_outbox_completion
        CHECK (
            (
                status IN ('SUCCEEDED', 'DEAD')
                AND completed_at IS NOT NULL
            )
            OR (
                status IN ('PENDING', 'PROCESSING')
                AND completed_at IS NULL
            )
        )
) ENGINE=InnoDB;
