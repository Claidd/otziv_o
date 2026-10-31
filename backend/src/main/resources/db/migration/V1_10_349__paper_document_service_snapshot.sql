ALTER TABLE paper_invoice_document
    ADD COLUMN service_name VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN vat_kind VARCHAR(24) NOT NULL DEFAULT 'without_nds';
