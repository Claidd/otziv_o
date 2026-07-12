CREATE TABLE IF NOT EXISTS worker_publication_sessions (
    worker_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    last_activity_at DATETIME(6) NOT NULL,
    business_date DATE NOT NULL,
    closed_at DATETIME(6) NULL,
    close_reason VARCHAR(40) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (worker_id),
    KEY idx_worker_publication_sessions_status_activity (status, last_activity_at),
    CONSTRAINT fk_worker_publication_sessions_worker
        FOREIGN KEY (worker_id) REFERENCES workers (worker_id)
        ON DELETE CASCADE
);
