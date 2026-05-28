ALTER TABLE payment_links
    ADD COLUMN payment_method VARCHAR(32) NOT NULL DEFAULT 'BANK_FORM',
    ADD COLUMN sbp_qr_payload VARCHAR(2048) NULL,
    ADD COLUMN sbp_qr_image TEXT NULL,
    ADD COLUMN sbp_qr_data_type VARCHAR(16) NULL,
    ADD COLUMN sbp_qr_created_at DATETIME(6) NULL;
