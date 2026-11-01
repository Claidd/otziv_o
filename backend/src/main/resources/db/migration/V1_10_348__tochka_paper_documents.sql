CREATE TABLE company_billing_payer (
    company_id BIGINT NOT NULL PRIMARY KEY,
    payer_json TEXT NOT NULL,
    updated_by VARCHAR(150) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
CREATE TABLE company_billing_payer_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    payer_json TEXT NOT NULL,
    changed_by VARCHAR(150) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    KEY idx_payer_audit_company (company_id,id)
);
CREATE TABLE paper_invoice_document (
    id CHAR(36) NOT NULL PRIMARY KEY,
    target_kind VARCHAR(24) NOT NULL,
    target_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    payment_link_id BIGINT NULL,
    number VARCHAR(96) NOT NULL,
    amount_kopecks BIGINT NOT NULL,
    payer_json TEXT NOT NULL,
    supplier_account VARCHAR(40) NOT NULL,
    customer_code VARCHAR(20) NOT NULL,
    composition_hash CHAR(64) NOT NULL,
    client_id VARCHAR(96) NOT NULL,
    group_id VARCHAR(200) NOT NULL,
    stage VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    bank_invoice_id VARCHAR(96) NULL,
    bank_act_id VARCHAR(96) NULL,
    invoice_pdf MEDIUMBLOB NULL,
    act_pdf MEDIUMBLOB NULL,
    invoice_delivery VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    act_delivery VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    invoice_message_id VARCHAR(512) NULL,
    act_message_id VARCHAR(512) NULL,
    error_text VARCHAR(700) NULL,
    created_by VARCHAR(150) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    paid_at DATETIME(6) NULL,
    UNIQUE KEY uk_paper_document_target (target_kind,target_id),
    UNIQUE KEY uk_paper_document_number (number),
    KEY idx_paper_document_work (stage,updated_at)
);
CREATE TABLE paper_invoice_bank_receipt (
    customer_code VARCHAR(20) NOT NULL,
    account_id VARCHAR(40) NOT NULL,
    payment_id VARCHAR(150) NOT NULL,
    document_id CHAR(36) NULL,
    amount_kopecks BIGINT NOT NULL,
    payer_inn VARCHAR(12) NOT NULL,
    purpose VARCHAR(2000) NOT NULL,
    state VARCHAR(32) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (customer_code,account_id,payment_id),
    KEY idx_paper_receipt_document (document_id,state)
);
ALTER TABLE payment_links ADD COLUMN paper_bank_document_id CHAR(36) NULL;
ALTER TABLE common_invoices ADD COLUMN paper_bank_document_id CHAR(36) NULL;
CREATE TABLE paper_invoice_statement_cursor (
    account_id VARCHAR(40) NOT NULL PRIMARY KEY,
    customer_code VARCHAR(20) NOT NULL,
    next_date DATE NOT NULL,
    statement_id VARCHAR(96) NULL,
    window_end DATE NULL,
    requested_at DATETIME(6) NULL,
    error_text VARCHAR(300) NULL
);
