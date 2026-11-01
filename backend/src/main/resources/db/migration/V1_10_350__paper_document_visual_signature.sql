ALTER TABLE paper_invoice_document
    ADD COLUMN invoice_original_pdf MEDIUMBLOB NULL,
    ADD COLUMN act_original_pdf MEDIUMBLOB NULL,
    ADD COLUMN signature_sha256 CHAR(64) NULL;
