-- Only hashes of client idempotency keys are persisted. command_scope must
-- include the operation and tenant/resource boundary. external_reference is for
-- non-secret provider IDs such as the TBank OrderId.
CREATE TABLE command_idempotency (
    command_idempotency_id BIGINT NOT NULL AUTO_INCREMENT,
    command_scope VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'PENDING',
    resource_type VARCHAR(100) NULL,
    resource_id VARCHAR(160) NULL,
    external_reference VARCHAR(160) NULL,
    response_status SMALLINT UNSIGNED NULL,
    response_body JSON NULL,
    processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    processing_owner VARCHAR(128) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_lease_until DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    last_error VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (command_idempotency_id),
    UNIQUE KEY uk_command_idempotency_scope_key_hash
        (command_scope, idempotency_key_hash),
    INDEX idx_command_idempotency_recovery
        (status, processing_lease_until, command_idempotency_id),
    INDEX idx_command_idempotency_expiry
        (expires_at, command_idempotency_id),
    INDEX idx_command_idempotency_external_reference
        (command_scope, external_reference),
    CONSTRAINT ck_command_idempotency_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_command_idempotency_processing_lease
        CHECK (
            (
                status = 'IN_PROGRESS'
                AND processing_token IS NOT NULL
                AND processing_owner IS NOT NULL
                AND processing_started_at IS NOT NULL
                AND processing_lease_until IS NOT NULL
                AND processing_lease_until > processing_started_at
            )
            OR (
                status <> 'IN_PROGRESS'
                AND processing_token IS NULL
                AND processing_owner IS NULL
                AND processing_started_at IS NULL
                AND processing_lease_until IS NULL
            )
        ),
    CONSTRAINT ck_command_idempotency_completion
        CHECK (
            (
                status IN ('SUCCEEDED', 'FAILED')
                AND completed_at IS NOT NULL
            )
            OR (
                status IN ('PENDING', 'IN_PROGRESS')
                AND completed_at IS NULL
            )
        ),
    CONSTRAINT ck_command_idempotency_resource
        CHECK (
            (resource_type IS NULL AND resource_id IS NULL)
            OR (resource_type IS NOT NULL AND resource_id IS NOT NULL)
        ),
    CONSTRAINT ck_command_idempotency_response
        CHECK (
            (response_status IS NULL OR response_status BETWEEN 100 AND 599)
            AND (response_body IS NULL OR response_status IS NOT NULL)
        )
) ENGINE=InnoDB;
