CREATE TABLE IF NOT EXISTS company_contacts (
    company_contact_id BIGINT NOT NULL AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contact_type VARCHAR(30) NOT NULL,
    contact_value VARCHAR(1000) NOT NULL,
    contact_normalized VARCHAR(1000) NULL,
    primary_contact TINYINT(1) NOT NULL DEFAULT 0,
    contact_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    source_lead_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (company_contact_id),
    CONSTRAINT fk_company_contacts_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    INDEX idx_company_contacts_company_type (company_id, contact_type),
    INDEX idx_company_contacts_type_normalized (contact_type, contact_normalized(191))
);

CREATE TABLE IF NOT EXISTS company_info (
    company_info_id BIGINT NOT NULL AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    region VARCHAR(255) NULL,
    address TEXT NULL,
    industries TEXT NULL,
    company_type TEXT NULL,
    info_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    source_lead_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL,
    PRIMARY KEY (company_info_id),
    UNIQUE KEY ux_company_info_company (company_id),
    CONSTRAINT fk_company_info_company
        FOREIGN KEY (company_id) REFERENCES companies (company_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);
