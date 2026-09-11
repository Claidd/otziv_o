-- Read-only screen projection; command handlers continue validating authoritative rows.
CREATE TABLE manager_control_read_snapshots (
    manager_id BIGINT NOT NULL,
    snapshot_date DATE NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    access_scope CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload JSON NULL,
    generated_at DATETIME(6) NULL,
    PRIMARY KEY (manager_id, snapshot_date),
    INDEX idx_manager_control_snapshot_date (snapshot_date)
) ENGINE=InnoDB;

CREATE TABLE projection_refresh_checkpoints (
    job_name VARCHAR(128) NOT NULL PRIMARY KEY,
    source_date DATE NOT NULL,
    last_id BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
