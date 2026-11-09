CREATE TABLE common_owner_receipt_approval (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    pending_invoice_id BIGINT NULL,
    request_key VARCHAR(48) NOT NULL,
    request_json TEXT NOT NULL,
    route_fingerprint CHAR(64) NOT NULL,
    attention_mode BOOLEAN NOT NULL,
    reported_by VARCHAR(160) NOT NULL,
    reported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decision VARCHAR(16) NULL,
    decided_by VARCHAR(160) NULL,
    decided_at DATETIME(6) NULL,
    UNIQUE KEY uk_common_owner_pending (pending_invoice_id),
    UNIQUE KEY uk_common_owner_request (invoice_id, request_key),
    FOREIGN KEY (invoice_id) REFERENCES common_invoices(invoice_id)
) ENGINE=InnoDB;
