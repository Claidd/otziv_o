-- Each newly created bonus has one durable Telegram delivery intent. Existing
-- adjustments are intentionally not backfilled to avoid old bonus messages.
CREATE TABLE contractor_bonus_telegram_notification_outbox (
    adjustment_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    processing_owner VARCHAR(128) NULL,
    processing_started_at DATETIME(6) NULL,
    processing_lease_until DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    delivery_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (adjustment_id),
    INDEX idx_contractor_bonus_telegram_notification_due
        (completed_at, next_attempt_at, processing_lease_until, adjustment_id),
    CONSTRAINT fk_contractor_bonus_telegram_notification_adjustment
        FOREIGN KEY (adjustment_id) REFERENCES contractor_accrual_adjustments (id),
    CONSTRAINT fk_contractor_bonus_telegram_notification_user
        FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT ck_contractor_bonus_telegram_notification_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_contractor_bonus_telegram_notification_state
        CHECK (
            (completed_at IS NULL AND delivery_state IS NULL)
            OR (completed_at IS NOT NULL AND delivery_state IN ('SENT', 'SKIPPED'))
        ),
    CONSTRAINT ck_contractor_bonus_telegram_notification_lease
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
