-- Completion-based contractor rewards are written once per immutable source,
-- role and professional identity. NULL keeps every legacy source outside this
-- constraint, so the rollout does not reinterpret historical rows.
ALTER TABLE zp
    ADD COLUMN zp_completion_idempotency_key VARCHAR(160)
        GENERATED ALWAYS AS (
            CASE
                WHEN zp_source IN (
                    'ORDER_COMPLETION_MANAGER',
                    'ORDER_COMPLETION_SPECIALIST',
                    'PERFORMER_PRODUCT_COMPLETION'
                )
                OR zp_source LIKE 'BAD_REVIEW_DONE_MANAGER:%'
                OR zp_source LIKE 'BAD_REVIEW_DONE_SPECIALIST:%'
                OR zp_source LIKE 'BAD_REVIEW_CANCEL_MANAGER:%'
                OR zp_source LIKE 'BAD_REVIEW_CANCEL_SPECIALIST:%'
                THEN CONCAT(
                    CAST(zp_order AS CHAR), '|',
                    zp_source, '|',
                    COALESCE(zp_contractor_role, ''), '|',
                    CAST(zp_profession AS CHAR)
                )
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE INDEX uk_zp_completion_source_profession (zp_completion_idempotency_key);

-- A marker freezes each logical attribution even when no recipient was
-- eligible and therefore no ZP row was created. Reruns only synchronize the
-- original rows; they never re-read a later manager/worker assignment.
CREATE TABLE contractor_completion_reward_markers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    logical_source VARCHAR(64) NOT NULL,
    occurred_on DATE NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_contractor_completion_reward_marker UNIQUE (order_id, logical_source),
    INDEX idx_contractor_completion_reward_marker_order (order_id)
) ENGINE=InnoDB;

-- Failed historical rows receive durable exponential backoff. Without this
-- state, a permanently malformed low-id order would occupy the first repair
-- page forever and starve all later completed orders.
CREATE TABLE contractor_completion_reward_repair_state (
    order_id BIGINT PRIMARY KEY,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(160) NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_completion_reward_repair_due (next_attempt_at, order_id)
) ENGINE=InnoDB;

-- Immutable accounting boundary. The row is created once, on the first
-- effective activation of completion attribution. Later edits of the mutable
-- app setting cannot move the boundary: a mismatch fails the runtime gate
-- closed. The singleton primary key also serializes concurrent first starts.
CREATE TABLE contractor_completion_cutover_state (
    id BIGINT PRIMARY KEY,
    attribution_start_date DATE NOT NULL,
    locked_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_contractor_completion_cutover_singleton CHECK (id = 1)
) ENGINE=InnoDB;

-- Deliberate operational sign-off after completion backfill and reconciliation.
-- LIVE routing remains closed even if both older rollout flags are enabled.
INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('contractor-payments.live-readiness-confirmed', 'false', CURRENT_TIMESTAMP(6)),
    ('contractor-payments.completion-attribution-start-date', '', CURRENT_TIMESTAMP(6));
