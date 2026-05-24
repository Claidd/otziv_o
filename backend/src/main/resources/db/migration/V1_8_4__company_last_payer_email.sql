ALTER TABLE companies
    ADD COLUMN company_last_payer_email VARCHAR(320) NULL,
    ADD COLUMN company_last_payer_email_at DATETIME(6) NULL;

CREATE INDEX idx_companies_last_payer_email ON companies (company_last_payer_email);
