ALTER TABLE companies
    CHANGE COLUMN company_ignore_worker_publication_date_risk
        company_allow_worker_publication_date_edit TINYINT(1) NOT NULL DEFAULT 0;
