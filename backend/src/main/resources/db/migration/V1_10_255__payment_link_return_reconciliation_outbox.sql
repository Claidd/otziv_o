CREATE TABLE payment_link_return_reconciliation_outbox (
    outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_link_id BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    observed_status VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(1000) NULL,
    processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (outbox_id),
    UNIQUE KEY uk_payment_link_return_outbox_source
        (payment_link_id, source_version, observed_status),
    INDEX idx_payment_link_return_outbox_due
        (status, next_attempt_at, lease_until, outbox_id),
    INDEX idx_payment_link_return_outbox_link
        (payment_link_id, status),
    CONSTRAINT ck_payment_link_return_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED')),
    CONSTRAINT ck_payment_link_return_outbox_attempts
        CHECK (attempt_count >= 0)
);

-- Existing live terminal observations also need a durable retry after rollout.
INSERT INTO payment_link_return_reconciliation_outbox (
    payment_link_id,
    source_version,
    observed_status
)
SELECT link.id,
       COALESCE(link.row_version, 0),
       link.status
FROM payment_links link
WHERE link.status IN ('REVERSED', 'PARTIAL_REVERSED', 'REFUNDED', 'PARTIAL_REFUNDED');
