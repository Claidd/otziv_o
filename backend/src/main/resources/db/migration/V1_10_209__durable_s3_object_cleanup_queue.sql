CREATE TABLE s3_object_cleanup_queue (
    cleanup_id BIGINT NOT NULL AUTO_INCREMENT,
    object_identity_hash BINARY(32) NOT NULL,
    bucket_name VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    cleanup_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error_code VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (cleanup_id),
    UNIQUE KEY uk_s3_cleanup_object_identity (object_identity_hash),
    INDEX idx_s3_cleanup_due (next_attempt_at, cleanup_id),
    CONSTRAINT ck_s3_cleanup_attempts CHECK (attempts <= 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
