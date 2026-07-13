CREATE TABLE IF NOT EXISTS review_account_pool_alert_state (
    state_id INT NOT NULL,
    last_remaining_count INT NULL,
    notified_threshold_mask INT NOT NULL DEFAULT 0,
    cycle_number BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (state_id)
);

INSERT INTO review_account_pool_alert_state (
    state_id,
    last_remaining_count,
    notified_threshold_mask,
    cycle_number,
    updated_at
)
VALUES (1, NULL, 0, 0, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE state_id = VALUES(state_id);
