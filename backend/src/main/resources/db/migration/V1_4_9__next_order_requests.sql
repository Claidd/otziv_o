CREATE TABLE IF NOT EXISTS next_order_requests (
    next_order_request_id BIGINT NOT NULL AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    filial_id BIGINT NULL,
    source_order_id BIGINT NOT NULL,
    created_order_id BIGINT NULL,
    request_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    error_message VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (next_order_request_id),
    UNIQUE KEY uk_next_order_requests_source_order (source_order_id),
    INDEX idx_next_order_requests_company_status (company_id, request_status),
    INDEX idx_next_order_requests_filial_status (company_id, filial_id, request_status),
    INDEX idx_next_order_requests_created_order (created_order_id),
    CONSTRAINT fk_next_order_requests_company
        FOREIGN KEY (company_id)
        REFERENCES companies (company_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_next_order_requests_filial
        FOREIGN KEY (filial_id)
        REFERENCES filial (filial_id)
        ON DELETE SET NULL
        ON UPDATE CASCADE,
    CONSTRAINT fk_next_order_requests_source_order
        FOREIGN KEY (source_order_id)
        REFERENCES orders (order_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_next_order_requests_created_order
        FOREIGN KEY (created_order_id)
        REFERENCES orders (order_id)
        ON DELETE SET NULL
        ON UPDATE CASCADE
) ENGINE=InnoDB;
