ALTER TABLE payment_links
    ADD COLUMN offer_consent_at DATETIME(6) NULL,
    ADD COLUMN privacy_consent_at DATETIME(6) NULL,
    ADD COLUMN receipt_consent_at DATETIME(6) NULL,
    ADD COLUMN consent_ip VARCHAR(128) NULL,
    ADD COLUMN consent_user_agent VARCHAR(512) NULL,
    ADD COLUMN offer_document_url VARCHAR(512) NULL,
    ADD COLUMN privacy_document_url VARCHAR(512) NULL,
    ADD COLUMN receipt_consent_document_url VARCHAR(512) NULL;
