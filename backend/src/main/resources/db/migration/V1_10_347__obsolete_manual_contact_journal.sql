-- Retiring an obsolete command never asserts delivery or changes its frozen provider operation.
CREATE TABLE manager_client_message_retirements (
    operation_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id BIGINT NOT NULL,
    reason VARCHAR(100) NOT NULL,
    previous_snapshot MEDIUMTEXT NOT NULL,
    current_source_snapshot TEXT NOT NULL,
    retired_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
