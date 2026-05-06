CREATE TABLE IF NOT EXISTS user_metric_snapshots (
    user_metric_snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    page_code VARCHAR(40) NOT NULL,
    metric_section VARCHAR(80) NOT NULL,
    metric_status VARCHAR(120) NOT NULL,
    last_seen_value INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_metric_snapshot_id),
    UNIQUE KEY uk_user_metric_snapshots_metric (user_id, page_code, metric_section, metric_status),
    INDEX idx_user_metric_snapshots_user_page (user_id, page_code),
    CONSTRAINT fk_user_metric_snapshots_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB;
