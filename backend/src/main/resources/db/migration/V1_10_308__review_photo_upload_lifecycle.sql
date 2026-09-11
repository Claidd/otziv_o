CREATE TABLE s3_upload_registry (
    upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_name VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    object_url VARCHAR(2048) NOT NULL,
    owner_id BIGINT NOT NULL,
    upload_state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    next_cleanup_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (upload_id),
    INDEX idx_upload_recovery (upload_state, next_cleanup_at),
    CONSTRAINT ck_upload_state CHECK (upload_state IN ('UPLOADING', 'READY', 'ATTACHED', 'ABANDONED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Orders owns the reference fence. Retired immutable URLs cannot be attached
-- again by the legacy URL editor while a storage delete is in flight.
CREATE TABLE review_photo_reference_guard (
    reference_hash BINARY(32) NOT NULL,
    retired BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (reference_hash)
) ENGINE=InnoDB;
