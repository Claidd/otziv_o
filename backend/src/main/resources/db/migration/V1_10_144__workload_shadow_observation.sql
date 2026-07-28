SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'workers'
      AND COLUMN_NAME = 'accepts_company_transfers'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE workers ADD COLUMN accepts_company_transfers BIT NOT NULL DEFAULT 1',
    'SELECT ''workers.accepts_company_transfers exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'workers'
      AND COLUMN_NAME = 'company_transfer_preference_changed_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE workers ADD COLUMN company_transfer_preference_changed_at DATETIME(6) NULL',
    'SELECT ''workers.company_transfer_preference_changed_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_created_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE bad_review_tasks ADD COLUMN bad_review_task_created_at DATETIME(6) NULL',
    'SELECT ''bad_review_tasks.bad_review_task_created_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_bad_review_tasks'
      AND COLUMN_NAME = 'bad_review_task_created_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE archive_bad_review_tasks ADD COLUMN bad_review_task_created_at DATETIME(6) NULL',
    'SELECT ''archive_bad_review_tasks.bad_review_task_created_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_created_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_created_at DATETIME(6) NULL',
    'SELECT ''reviews.review_created_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_vigul_changed_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_vigul_changed_at DATETIME(6) NULL',
    'SELECT ''reviews.review_vigul_changed_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Existing rows are not rewritten. The shadow queries use legacy review/order
-- timestamps as a read-time fallback, while new transitions are captured exactly.

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_reviews'
      AND COLUMN_NAME = 'review_created_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE archive_reviews ADD COLUMN review_created_at DATETIME(6) NULL',
    'SELECT ''archive_reviews.review_created_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_reviews'
      AND COLUMN_NAME = 'review_vigul_changed_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE archive_reviews ADD COLUMN review_vigul_changed_at DATETIME(6) NULL',
    'SELECT ''archive_reviews.review_vigul_changed_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_text_ready_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_text_ready_at DATETIME(6) NULL',
    'SELECT ''reviews.review_text_ready_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND COLUMN_NAME = 'review_text_ready_worker_id'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE reviews ADD COLUMN review_text_ready_worker_id BIGINT NULL',
    'SELECT ''reviews.review_text_ready_worker_id exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_reviews'
      AND COLUMN_NAME = 'review_text_ready_at'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE archive_reviews ADD COLUMN review_text_ready_at DATETIME(6) NULL',
    'SELECT ''archive_reviews.review_text_ready_at exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'archive_reviews'
      AND COLUMN_NAME = 'review_text_ready_worker_id'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE archive_reviews ADD COLUMN review_text_ready_worker_id BIGINT NULL',
    'SELECT ''archive_reviews.review_text_ready_worker_id exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND INDEX_NAME = 'idx_reviews_worker_publish_marked'
);
SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_worker_publish_marked (review_worker, review_publish, review_published_marked_at, review_id)',
    'SELECT ''idx_reviews_worker_publish_marked exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND INDEX_NAME = 'idx_reviews_worker_text_ready'
);
SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_worker_text_ready (review_text_ready_worker_id, review_text_ready_at)',
    'SELECT ''idx_reviews_worker_text_ready exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'business_audit_events'
      AND INDEX_NAME = 'idx_business_audit_action_order_created'
);
SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE business_audit_events ADD INDEX idx_business_audit_action_order_created (action, order_id, created_at)',
    'SELECT ''idx_business_audit_action_order_created exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'business_audit_events'
      AND INDEX_NAME = 'idx_business_audit_action_review_created'
);
SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE business_audit_events ADD INDEX idx_business_audit_action_review_created (action, review_id, created_at)',
    'SELECT ''idx_business_audit_action_review_created exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'worker_activity_events'
      AND INDEX_NAME = 'idx_worker_activity_review_action_created'
);
SET @sql = IF(
    @index_exists = 0,
    'ALTER TABLE worker_activity_events ADD INDEX idx_worker_activity_review_action_created (review_id, action, created_at)',
    'SELECT ''idx_worker_activity_review_action_created exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS workload_shadow_runs (
    workload_shadow_run_id BIGINT NOT NULL AUTO_INCREMENT,
    trigger_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    duration_ms BIGINT NULL,
    manager_count INT NOT NULL DEFAULT 0,
    worker_count INT NOT NULL DEFAULT 0,
    transfer_case_count INT NOT NULL DEFAULT 0,
    event_count INT NOT NULL DEFAULT 0,
    self_heal_action_count INT NOT NULL DEFAULT 0,
    instance_id VARCHAR(120) NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(1000) NULL,
    PRIMARY KEY (workload_shadow_run_id),
    INDEX idx_workload_shadow_runs_started (started_at),
    INDEX idx_workload_shadow_runs_status_started (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_recalculation_locks (
    lock_name VARCHAR(80) NOT NULL,
    owner_instance_id VARCHAR(120) NULL,
    owner_token CHAR(36) NULL,
    run_id BIGINT NULL,
    acquired_at DATETIME(6) NULL,
    renewed_at DATETIME(6) NULL,
    lease_until DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    takeover_count BIGINT NOT NULL DEFAULT 0,
    last_recovered_at DATETIME(6) NULL,
    last_released_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lock_name),
    UNIQUE KEY uk_workload_shadow_recalculation_owner_token (owner_token),
    INDEX idx_workload_shadow_recalculation_lease (lease_until),
    CONSTRAINT fk_workload_shadow_recalculation_run
        FOREIGN KEY (run_id) REFERENCES workload_shadow_runs (workload_shadow_run_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO workload_shadow_recalculation_locks (
    lock_name,
    lease_until
) VALUES (
    'GLOBAL_RECALCULATION',
    '1970-01-01 00:00:00.000000'
);

CREATE TABLE IF NOT EXISTS workload_shadow_worker_current (
    worker_id BIGINT NOT NULL,
    worker_user_id BIGINT NULL,
    manager_id BIGINT NULL,
    progress_date DATE NOT NULL,
    snapshot_at DATETIME(6) NOT NULL,
    completed_units BIGINT NOT NULL DEFAULT 0,
    active_units BIGINT NOT NULL DEFAULT 0,
    late_excluded_units BIGINT NOT NULL DEFAULT 0,
    eligible_units BIGINT NOT NULL DEFAULT 0,
    progress_percent DECIMAL(5,2) NOT NULL DEFAULT 100,
    feasible_units BIGINT NOT NULL DEFAULT 0,
    estimated_remaining_minutes BIGINT NOT NULL DEFAULT 0,
    planned_units BIGINT NOT NULL DEFAULT 0,
    incoming_units BIGINT NOT NULL DEFAULT 0,
    urgent_units BIGINT NOT NULL DEFAULT 0,
    external_blocked_units BIGINT NOT NULL DEFAULT 0,
    client_deferred_units BIGINT NOT NULL DEFAULT 0,
    manager_deferred_units BIGINT NOT NULL DEFAULT 0,
    new_units BIGINT NOT NULL DEFAULT 0,
    correction_units BIGINT NOT NULL DEFAULT 0,
    nagul_units BIGINT NOT NULL DEFAULT 0,
    publish_units BIGINT NOT NULL DEFAULT 0,
    recovery_units BIGINT NOT NULL DEFAULT 0,
    bad_units BIGINT NOT NULL DEFAULT 0,
    rating DECIMAL(5,2) NOT NULL DEFAULT 0,
    hundred_percent_days INT NOT NULL DEFAULT 0,
    failure_days INT NOT NULL DEFAULT 0,
    freeze_credits INT NOT NULL DEFAULT 0,
    transfer_stage INT NOT NULL DEFAULT 0,
    last_day_reached_100 BIT NOT NULL DEFAULT 0,
    accepts_company_transfers BIT NOT NULL DEFAULT 1,
    recipient_eligible BIT NOT NULL DEFAULT 0,
    worker_group_connected BIT NOT NULL DEFAULT 0,
    diagnostic_status VARCHAR(32) NOT NULL DEFAULT 'OK',
    last_available_at DATETIME(6) NULL,
    run_id BIGINT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (worker_id),
    INDEX idx_workload_shadow_current_manager (manager_id, progress_percent, rating),
    INDEX idx_workload_shadow_current_stage (transfer_stage, recipient_eligible),
    CONSTRAINT fk_workload_shadow_current_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE,
    CONSTRAINT fk_workload_shadow_current_user
        FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_workload_shadow_current_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id) ON DELETE SET NULL,
    CONSTRAINT fk_workload_shadow_current_run
        FOREIGN KEY (run_id) REFERENCES workload_shadow_runs (workload_shadow_run_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_worker_daily (
    workload_shadow_worker_daily_id BIGINT NOT NULL AUTO_INCREMENT,
    progress_date DATE NOT NULL,
    worker_id BIGINT NOT NULL,
    worker_user_id BIGINT NULL,
    manager_id BIGINT NULL,
    completed_units BIGINT NOT NULL DEFAULT 0,
    active_units BIGINT NOT NULL DEFAULT 0,
    late_excluded_units BIGINT NOT NULL DEFAULT 0,
    eligible_units BIGINT NOT NULL DEFAULT 0,
    progress_percent DECIMAL(5,2) NOT NULL DEFAULT 100,
    rating DECIMAL(5,2) NOT NULL DEFAULT 0,
    planned_units BIGINT NOT NULL DEFAULT 0,
    incoming_units BIGINT NOT NULL DEFAULT 0,
    urgent_units BIGINT NOT NULL DEFAULT 0,
    external_blocked_units BIGINT NOT NULL DEFAULT 0,
    client_deferred_units BIGINT NOT NULL DEFAULT 0,
    manager_deferred_units BIGINT NOT NULL DEFAULT 0,
    reached_100 BIT NOT NULL DEFAULT 0,
    freeze_applied BIT NOT NULL DEFAULT 0,
    finalized BIT NOT NULL DEFAULT 0,
    finalization_status VARCHAR(32) NOT NULL DEFAULT 'LIVE',
    first_snapshot_at DATETIME(6) NOT NULL,
    last_snapshot_at DATETIME(6) NOT NULL,
    finalized_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_shadow_worker_daily_id),
    UNIQUE KEY uk_workload_shadow_worker_daily (progress_date, worker_id),
    INDEX idx_workload_shadow_daily_worker_date (worker_id, progress_date),
    INDEX idx_workload_shadow_daily_manager_date (manager_id, progress_date),
    INDEX idx_workload_shadow_daily_finalized (finalized, progress_date),
    CONSTRAINT fk_workload_shadow_daily_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE,
    CONSTRAINT fk_workload_shadow_daily_user
        FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_workload_shadow_daily_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_late_batches (
    progress_date DATE NOT NULL,
    worker_id BIGINT NOT NULL,
    batch_key VARCHAR(190) NOT NULL,
    section_code VARCHAR(32) NOT NULL,
    initial_units BIGINT NOT NULL DEFAULT 0,
    remaining_units BIGINT NOT NULL DEFAULT 0,
    initial_estimated_minutes BIGINT NOT NULL DEFAULT 0,
    remaining_estimated_minutes BIGINT NOT NULL DEFAULT 0,
    first_detected_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    active BIT NOT NULL DEFAULT 1,
    PRIMARY KEY (progress_date, worker_id, batch_key),
    INDEX idx_workload_shadow_late_worker_date (worker_id, progress_date, active),
    INDEX idx_workload_shadow_late_seen (last_seen_at),
    CONSTRAINT fk_workload_shadow_late_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_freeze_accounts (
    worker_id BIGINT NOT NULL,
    available_credits INT NOT NULL DEFAULT 0,
    successful_days_since_credit INT NOT NULL DEFAULT 0,
    earned_total INT NOT NULL DEFAULT 0,
    simulated_used_total INT NOT NULL DEFAULT 0,
    last_evaluated_date DATE NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (worker_id),
    CONSTRAINT fk_workload_shadow_freeze_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_estimate_stats (
    section_code VARCHAR(32) NOT NULL,
    sample_count BIGINT NOT NULL DEFAULT 0,
    average_seconds BIGINT NOT NULL DEFAULT 0,
    effective_minutes INT NOT NULL,
    minimum_minutes INT NOT NULL,
    estimate_source VARCHAR(24) NOT NULL DEFAULT 'DEFAULT',
    calculated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (section_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_transfer_cases (
    workload_shadow_transfer_case_id BIGINT NOT NULL AUTO_INCREMENT,
    case_key VARCHAR(160) NOT NULL,
    manager_id BIGINT NOT NULL,
    source_worker_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    company_title VARCHAR(500) NULL,
    failure_number INT NOT NULL,
    transfer_percent INT NOT NULL,
    selection_rank INT NOT NULL,
    problem_units BIGINT NOT NULL DEFAULT 0,
    estimated_minutes BIGINT NOT NULL DEFAULT 0,
    active_order_count BIGINT NOT NULL DEFAULT 0,
    new_unit_count BIGINT NOT NULL DEFAULT 0,
    correction_count BIGINT NOT NULL DEFAULT 0,
    nagul_count BIGINT NOT NULL DEFAULT 0,
    publish_count BIGINT NOT NULL DEFAULT 0,
    recovery_count BIGINT NOT NULL DEFAULT 0,
    bad_count BIGINT NOT NULL DEFAULT 0,
    graph_warning_count INT NOT NULL DEFAULT 0,
    graph_error_count INT NOT NULL DEFAULT 0,
    graph_warning_codes VARCHAR(1000) NOT NULL DEFAULT '',
    graph_error_codes VARCHAR(1000) NOT NULL DEFAULT '',
    candidate_count INT NOT NULL DEFAULT 0,
    staffing_required BIT NOT NULL DEFAULT 0,
    fallback_worker_id BIGINT NULL,
    fallback_review_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SHADOW_PENDING',
    active BIT NOT NULL DEFAULT 1,
    run_id BIGINT NULL,
    first_detected_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (workload_shadow_transfer_case_id),
    UNIQUE KEY uk_workload_shadow_transfer_case_key (case_key),
    INDEX idx_workload_shadow_transfer_manager (manager_id, active, selection_rank),
    INDEX idx_workload_shadow_transfer_source (source_worker_id, active),
    INDEX idx_workload_shadow_transfer_company (company_id, active),
    INDEX idx_workload_shadow_transfer_fallback_review (fallback_review_id),
    INDEX idx_workload_shadow_transfer_graph_health (active, graph_error_count, graph_warning_count),
    INDEX idx_workload_shadow_transfer_retention (
        active,
        resolved_at,
        workload_shadow_transfer_case_id
    ),
    CONSTRAINT fk_workload_shadow_transfer_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id) ON DELETE CASCADE,
    CONSTRAINT fk_workload_shadow_transfer_source_worker
        FOREIGN KEY (source_worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE,
    CONSTRAINT fk_workload_shadow_transfer_fallback_worker
        FOREIGN KEY (fallback_worker_id) REFERENCES workers (worker_id) ON DELETE SET NULL,
    CONSTRAINT fk_workload_shadow_transfer_fallback_review
        FOREIGN KEY (fallback_review_id) REFERENCES reviews (review_id) ON DELETE SET NULL,
    CONSTRAINT fk_workload_shadow_transfer_run
        FOREIGN KEY (run_id) REFERENCES workload_shadow_runs (workload_shadow_run_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_transfer_candidates (
    workload_shadow_transfer_candidate_id BIGINT NOT NULL AUTO_INCREMENT,
    transfer_case_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    rating DECIMAL(5,2) NOT NULL DEFAULT 0,
    hundred_percent_days INT NOT NULL DEFAULT 0,
    failure_days INT NOT NULL DEFAULT 0,
    current_estimated_minutes BIGINT NOT NULL DEFAULT 0,
    worker_group_connected BIT NOT NULL DEFAULT 0,
    simulated_offer_status VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workload_shadow_transfer_candidate_id),
    UNIQUE KEY uk_workload_shadow_transfer_candidate (transfer_case_id, worker_id),
    INDEX idx_workload_shadow_candidate_sequence (transfer_case_id, sequence_number),
    INDEX idx_workload_shadow_candidate_worker (worker_id, created_at),
    CONSTRAINT fk_workload_shadow_candidate_case
        FOREIGN KEY (transfer_case_id) REFERENCES workload_shadow_transfer_cases (workload_shadow_transfer_case_id) ON DELETE CASCADE,
    CONSTRAINT fk_workload_shadow_candidate_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workload_shadow_events (
    workload_shadow_event_id BIGINT NOT NULL AUTO_INCREMENT,
    deduplication_key VARCHAR(190) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    manager_id BIGINT NULL,
    worker_id BIGINT NULL,
    company_id BIGINT NULL,
    transfer_case_id BIGINT NULL,
    title VARCHAR(220) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    target_group_type VARCHAR(24) NOT NULL,
    target_group_chat_id BIGINT NULL,
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    delivery_attempts INT NOT NULL DEFAULT 0,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    delivered_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    active BIT NOT NULL DEFAULT 1,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (workload_shadow_event_id),
    UNIQUE KEY uk_workload_shadow_event_dedup (deduplication_key),
    INDEX idx_workload_shadow_event_due (
        active,
        delivery_status,
        next_attempt_at,
        first_seen_at,
        workload_shadow_event_id
    ),
    INDEX idx_workload_shadow_event_retention (
        active,
        delivery_status,
        last_seen_at,
        workload_shadow_event_id
    ),
    INDEX idx_workload_shadow_event_monitor (
        active,
        last_seen_at,
        workload_shadow_event_id
    ),
    INDEX idx_workload_shadow_event_last_seen (last_seen_at),
    INDEX idx_workload_shadow_event_manager (manager_id, active, severity),
    CONSTRAINT fk_workload_shadow_event_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id) ON DELETE SET NULL,
    CONSTRAINT fk_workload_shadow_event_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE SET NULL,
    CONSTRAINT fk_workload_shadow_event_case
        FOREIGN KEY (transfer_case_id) REFERENCES workload_shadow_transfer_cases (workload_shadow_transfer_case_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.shadow.observation-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.apply-enabled', 'false', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.group-notifications-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.scheduler-interval-minutes', '10', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.near-end-interval-minutes', '5', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.near-end-window-minutes', '120', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.business-zone', 'Asia/Irkutsk', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.shift-start', '10:00', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.shift-end', '23:00', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.walk-minutes-per-card', '4', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.walk-minimum-minutes-per-card', '3', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.new-minutes-per-card', '5', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.correction-minutes-per-order', '10', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.publish-minutes-per-card', '3', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.recovery-minutes-per-task', '10', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.bad-minutes-per-task', '10', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.adaptive-estimates-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.adaptive-minimum-samples', '30', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.lookback-days', '30', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.allowed-failure-days', '3', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.recipient-minimum-rating', '85', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.recipient-minimum-100-rate', '80', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.recipient-maximum-failure-days', '2', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.fourth-failure-percent', '15', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.fourth-failure-max-companies', '1', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.fifth-failure-percent', '25', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.fifth-failure-max-companies', '2', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.sixth-failure-percent', '30', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.sixth-failure-max-companies', '3', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.freeze-earn-days', '14', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.freeze-max-credits', '2', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.alert-cooldown-minutes', '60', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.run-retention-days', '30', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.daily-retention-days', '400', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.event-retention-days', '90', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.stale-run-minutes', '30', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.settings-revision', '1', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
