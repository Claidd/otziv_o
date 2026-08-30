-- Durable, fenced delivery of replacement payment details after an ordinary
-- order changes its payment route.  The row is inserted in the same
-- transaction that creates the replacement payment_link, so a process crash
-- between COMMIT and the in-memory afterCommit callback cannot lose the
-- customer notification.
CREATE TABLE payment_route_change_notification_outbox (
    payment_link_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    processing_owner VARCHAR(128) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_lease_until DATETIME(6) NULL,
    last_error VARCHAR(512) NULL,
    sent_at DATETIME(6) NULL,
    skipped_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (payment_link_id),
    INDEX idx_payment_route_change_notification_due
        (sent_at, skipped_at, next_attempt_at, processing_lease_until, payment_link_id),
    INDEX idx_payment_route_change_notification_order (order_id, payment_link_id),
    CONSTRAINT ck_payment_route_change_notification_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_payment_route_change_notification_terminal
        CHECK (NOT (sent_at IS NOT NULL AND skipped_at IS NOT NULL)),
    CONSTRAINT ck_payment_route_change_notification_lease
        CHECK (
            (processing_token IS NULL
                AND processing_owner IS NULL
                AND processing_started_at IS NULL
                AND processing_lease_until IS NULL)
            OR
            (processing_token IS NOT NULL
                AND processing_owner IS NOT NULL
                AND processing_started_at IS NOT NULL
                AND processing_lease_until > processing_started_at)
        )
) ENGINE=InnoDB;
