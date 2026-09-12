CREATE TABLE worker_team_pattern_snapshots (
    worker_id BIGINT NOT NULL,
    month_start DATE NOT NULL,
    user_id BIGINT NOT NULL,
    from_date DATE NOT NULL,
    to_exclusive DATE NOT NULL,
    generated_at_utc DATETIME(6) NOT NULL,
    payload JSON NOT NULL,
    PRIMARY KEY (worker_id, month_start),
    KEY idx_team_pattern_retention (month_start)
) ENGINE=InnoDB;
