-- Owner/admin action "Засчитать день": one row per specialist day that was excused.
-- The row keeps every original value the action changed, so the decision can be undone
-- exactly. Real eligible/completed numbers are never changed by the action itself.
CREATE TABLE IF NOT EXISTS worker_day_excuses (
    worker_day_excuse_id BIGINT NOT NULL AUTO_INCREMENT,
    worker_id BIGINT NOT NULL,
    result_date DATE NOT NULL,
    active BIT NOT NULL DEFAULT b'1',
    reason VARCHAR(500) NOT NULL,
    excused_by VARCHAR(150) NOT NULL,
    excused_at DATETIME(6) NOT NULL,
    original_reached_100 BIT NOT NULL,
    original_daily_status VARCHAR(24) NULL,
    original_daily_reached_100 BIT NULL,
    original_daily_finalized_at DATETIME(6) NULL,
    original_workload_status VARCHAR(32) NULL,
    original_workload_reached_100 BIT NULL,
    revoked_by VARCHAR(150) NULL,
    revoked_at DATETIME(6) NULL,
    revoke_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (worker_day_excuse_id),
    UNIQUE KEY uk_worker_day_excuse_worker_date (worker_id, result_date),
    INDEX idx_worker_day_excuse_date (result_date, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
