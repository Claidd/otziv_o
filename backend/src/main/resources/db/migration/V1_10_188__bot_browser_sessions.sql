CREATE TABLE bot_browser_sessions (
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bot_id BIGINT NOT NULL,
    external_key_snapshot VARCHAR(96) NOT NULL,
    opener_username VARCHAR(255) NOT NULL,
    opener_subject VARCHAR(512) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    opened_at DATETIME(6) NULL,
    last_heartbeat_at DATETIME(6) NOT NULL,
    heartbeat_expires_at DATETIME(6) NOT NULL,
    absolute_expires_at DATETIME(6) NOT NULL,
    close_requested_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    next_stop_retry_at DATETIME(6) NULL,
    stop_attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    active_bot_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('OPENING', 'OPEN', 'CLOSING', 'STOP_RETRY') THEN bot_id
                ELSE NULL
            END
        ) STORED,
    PRIMARY KEY (session_id),
    UNIQUE KEY uk_bot_browser_sessions_active_bot (active_bot_id),
    KEY idx_bot_browser_sessions_heartbeat_sweep
        (status, heartbeat_expires_at, session_id),
    KEY idx_bot_browser_sessions_absolute_sweep
        (status, absolute_expires_at, session_id),
    KEY idx_bot_browser_sessions_retry_sweep
        (status, next_stop_retry_at, session_id),
    KEY idx_bot_browser_sessions_transition_sweep
        (status, updated_at, session_id),
    KEY idx_bot_browser_sessions_opener (opener_subject, created_at),
    KEY idx_bot_browser_sessions_bot_history (bot_id, created_at),
    CONSTRAINT chk_bot_browser_sessions_status
        CHECK (status IN ('OPENING', 'OPEN', 'CLOSING', 'STOP_RETRY', 'CLOSED')),
    CONSTRAINT chk_bot_browser_sessions_counters
        CHECK (stop_attempts >= 0 AND version >= 0),
    CONSTRAINT chk_bot_browser_sessions_expiry
        CHECK (
            created_at <= updated_at
            AND created_at <= last_heartbeat_at
            AND last_heartbeat_at <= heartbeat_expires_at
            AND heartbeat_expires_at <= absolute_expires_at
        ),
    CONSTRAINT chk_bot_browser_sessions_lifecycle_times
        CHECK (
            (opened_at IS NULL OR opened_at >= created_at)
            AND (close_requested_at IS NULL OR close_requested_at >= created_at)
            AND (closed_at IS NULL OR closed_at >= created_at)
        ),
    CONSTRAINT chk_bot_browser_sessions_state_shape
        CHECK (
            (status <> 'OPEN' OR opened_at IS NOT NULL)
            AND (status NOT IN ('CLOSING', 'STOP_RETRY', 'CLOSED') OR close_requested_at IS NOT NULL)
            AND (status <> 'STOP_RETRY' OR next_stop_retry_at IS NOT NULL)
            AND (status <> 'CLOSED' OR closed_at IS NOT NULL)
        )
) ENGINE=InnoDB;
