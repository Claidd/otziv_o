CREATE TABLE IF NOT EXISTS common_invoice_payment_refs (
    payment_ref_id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT NOT NULL,
    tbank_order_id VARCHAR(36) NULL,
    tbank_payment_id VARCHAR(64) NULL,
    tbank_terminal_key VARCHAR(64) NULL,
    amount_kopecks BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ARCHIVED',
    reason VARCHAR(160) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (payment_ref_id),
    UNIQUE KEY uk_common_invoice_payment_ref_order (tbank_order_id),
    UNIQUE KEY uk_common_invoice_payment_ref_payment (tbank_payment_id),
    INDEX idx_common_invoice_payment_refs_invoice (invoice_id),
    CONSTRAINT fk_common_invoice_payment_refs_invoice FOREIGN KEY (invoice_id) REFERENCES common_invoices (invoice_id)
);
