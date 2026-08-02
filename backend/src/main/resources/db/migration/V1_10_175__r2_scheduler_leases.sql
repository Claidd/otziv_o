CREATE TABLE scheduler_leases (
    lease_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_instance VARCHAR(128) NOT NULL,
    fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 1,
    acquired_at DATETIME(6) NOT NULL,
    heartbeat_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lease_name),
    INDEX idx_scheduler_leases_expiry (lease_until, lease_name),
    CONSTRAINT ck_scheduler_leases_fencing_token
        CHECK (fencing_token > 0),
    CONSTRAINT ck_scheduler_leases_timestamps
        CHECK (
            heartbeat_at >= acquired_at
            AND lease_until > heartbeat_at
        )
) ENGINE=InnoDB;
