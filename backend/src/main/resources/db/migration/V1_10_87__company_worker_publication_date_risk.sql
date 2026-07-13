ALTER TABLE companies
    ADD COLUMN company_ignore_worker_publication_date_risk TINYINT(1) NOT NULL DEFAULT 0;
