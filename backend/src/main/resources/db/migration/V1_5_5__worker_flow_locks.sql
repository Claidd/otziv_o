CREATE TABLE IF NOT EXISTS worker_flow_locks (
    lock_key VARCHAR(150) NOT NULL,
    worker_id BIGINT NULL,
    locked_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (lock_key),
    INDEX idx_worker_flow_locks_worker (worker_id),
    CONSTRAINT fk_worker_flow_locks_worker
        FOREIGN KEY (worker_id)
        REFERENCES workers (worker_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB;
