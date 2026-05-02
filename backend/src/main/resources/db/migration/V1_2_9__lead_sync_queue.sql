CREATE TABLE lead_sync_queue (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 telephone_lead VARCHAR(20) NOT NULL,
                                 last_seen DATETIME NULL,
                                 lid_status VARCHAR(50),
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

