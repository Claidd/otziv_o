-- Pending documents only. Successful delivery removes the entire row and its contents.
CREATE TABLE bot_duplicate_report_delivery (
    report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    file_name VARCHAR(128) NOT NULL,
    report_text MEDIUMTEXT NOT NULL,
    pending_chat_ids TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    INDEX idx_bot_duplicate_report_due (next_attempt_at, report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
