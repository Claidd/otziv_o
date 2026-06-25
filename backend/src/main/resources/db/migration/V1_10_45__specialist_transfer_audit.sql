CREATE TABLE IF NOT EXISTS specialist_transfer_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    from_worker_id BIGINT NOT NULL,
    to_worker_id BIGINT NOT NULL,
    company_count INT NOT NULL DEFAULT 0,
    order_count INT NOT NULL DEFAULT 0,
    review_count INT NOT NULL DEFAULT 0,
    company_ids_json JSON NULL,
    comment VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_specialist_transfer_actor_user
        FOREIGN KEY (actor_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_specialist_transfer_from_worker
        FOREIGN KEY (from_worker_id) REFERENCES workers (worker_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_specialist_transfer_to_worker
        FOREIGN KEY (to_worker_id) REFERENCES workers (worker_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    INDEX idx_specialist_transfer_created (created_at, id),
    INDEX idx_specialist_transfer_from_worker (from_worker_id, created_at),
    INDEX idx_specialist_transfer_to_worker (to_worker_id, created_at)
);
