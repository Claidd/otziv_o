INSERT INTO order_statuses (order_status_title)
SELECT 'Ожидает общего счета'
WHERE NOT EXISTS (
    SELECT 1 FROM order_statuses WHERE order_status_title = 'Ожидает общего счета'
);

CREATE TABLE IF NOT EXISTS common_billing_accounts (
    account_id BIGINT NOT NULL AUTO_INCREMENT,
    account_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_repeat_orders BOOLEAN NOT NULL DEFAULT TRUE,
    manager_id BIGINT NULL,
    invoice_company_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (account_id),
    INDEX idx_common_billing_accounts_enabled (enabled, account_id),
    CONSTRAINT fk_common_billing_accounts_manager FOREIGN KEY (manager_id) REFERENCES managers (manager_id),
    CONSTRAINT fk_common_billing_accounts_invoice_company FOREIGN KEY (invoice_company_id) REFERENCES companies (company_id)
);

CREATE TABLE IF NOT EXISTS common_billing_account_companies (
    account_company_id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (account_company_id),
    UNIQUE KEY uk_common_billing_account_company (account_id, company_id),
    INDEX idx_common_billing_company_enabled (company_id, enabled),
    CONSTRAINT fk_common_billing_account_companies_account FOREIGN KEY (account_id) REFERENCES common_billing_accounts (account_id),
    CONSTRAINT fk_common_billing_account_companies_company FOREIGN KEY (company_id) REFERENCES companies (company_id)
);

CREATE TABLE IF NOT EXISTS common_invoices (
    invoice_id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    token VARCHAR(96) NOT NULL,
    title VARCHAR(180) NOT NULL,
    status VARCHAR(32) NOT NULL,
    amount_kopecks BIGINT NOT NULL DEFAULT 0,
    paid_kopecks BIGINT NOT NULL DEFAULT 0,
    tbank_order_id VARCHAR(36) NULL,
    tbank_payment_id VARCHAR(64) NULL,
    tbank_terminal_key VARCHAR(64) NULL,
    tbank_payment_amount_kopecks BIGINT NULL,
    payment_url VARCHAR(1024) NULL,
    payer_email VARCHAR(320) NULL,
    last_error VARCHAR(512) NULL,
    sent_at DATETIME(6) NULL,
    last_reminder_at DATETIME(6) NULL,
    next_reminder_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (invoice_id),
    UNIQUE KEY uk_common_invoices_token (token),
    UNIQUE KEY uk_common_invoices_tbank_order_id (tbank_order_id),
    INDEX idx_common_invoices_account_status (account_id, status, invoice_id),
    INDEX idx_common_invoices_reminder (status, next_reminder_at),
    INDEX idx_common_invoices_tbank_payment_id (tbank_payment_id),
    CONSTRAINT fk_common_invoices_account FOREIGN KEY (account_id) REFERENCES common_billing_accounts (account_id)
);

CREATE TABLE IF NOT EXISTS common_invoice_orders (
    invoice_order_id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    amount_kopecks BIGINT NOT NULL DEFAULT 0,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    paid BOOLEAN NOT NULL DEFAULT FALSE,
    unpaid BOOLEAN NOT NULL DEFAULT FALSE,
    paid_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (invoice_order_id),
    UNIQUE KEY uk_common_invoice_order (order_id),
    INDEX idx_common_invoice_orders_invoice (invoice_id, ready, paid, unpaid),
    CONSTRAINT fk_common_invoice_orders_invoice FOREIGN KEY (invoice_id) REFERENCES common_invoices (invoice_id),
    CONSTRAINT fk_common_invoice_orders_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
);
