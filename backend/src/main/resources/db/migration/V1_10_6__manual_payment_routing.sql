ALTER TABLE payment_profiles
    ADD COLUMN payment_policy VARCHAR(48) NOT NULL DEFAULT 'T_BANK_ONLY',
    ADD COLUMN manual_phone VARCHAR(32) NULL,
    ADD COLUMN manual_recipient_name VARCHAR(160) NULL,
    ADD COLUMN manual_monthly_soft_limit_kopecks BIGINT NULL,
    ADD COLUMN manual_monthly_hard_limit_kopecks BIGINT NULL,
    ADD INDEX idx_payment_profiles_policy (payment_policy);

ALTER TABLE payment_links
    ADD COLUMN reserved_amount_kopecks BIGINT NULL,
    ADD COLUMN confirmed_amount_kopecks BIGINT NULL,
    ADD COLUMN manual_phone VARCHAR(32) NULL,
    ADD COLUMN manual_recipient_name VARCHAR(160) NULL,
    ADD COLUMN manual_comment VARCHAR(255) NULL,
    ADD COLUMN manual_reported_at DATETIME(6) NULL,
    ADD COLUMN manual_confirmed_by VARCHAR(160) NULL,
    ADD COLUMN manual_confirmed_at DATETIME(6) NULL,
    ADD COLUMN receipt_status VARCHAR(32) NULL,
    ADD INDEX idx_payment_links_manual_profile_month (payment_profile_id, payment_method, created_at);

UPDATE payment_links
SET reserved_amount_kopecks = amount_kopecks
WHERE reserved_amount_kopecks IS NULL;
