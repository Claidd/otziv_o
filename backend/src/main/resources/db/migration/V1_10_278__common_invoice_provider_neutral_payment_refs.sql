ALTER TABLE common_invoice_payment_refs
    ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'T_BANK' AFTER tbank_terminal_key,
    ADD COLUMN payment_profile_id BIGINT NULL AFTER provider,
    ADD COLUMN provider_order_id VARCHAR(64) NULL AFTER payment_profile_id,
    ADD COLUMN provider_payment_id VARCHAR(64) NULL AFTER provider_order_id,
    ADD COLUMN provider_merchant_id VARCHAR(64) NULL AFTER provider_payment_id,
    ADD COLUMN provider_payment_mode VARCHAR(32) NULL AFTER provider_merchant_id,
    ADD COLUMN provider_test_mode BOOLEAN NULL AFTER provider_payment_mode,
    ADD COLUMN provider_status VARCHAR(32) NULL AFTER provider_test_mode,
    ADD COLUMN provider_payment_url VARCHAR(1024) NULL AFTER provider_status,
    ADD COLUMN provider_expires_at DATETIME(6) NULL AFTER provider_payment_url;

ALTER TABLE archive_common_invoice_payment_refs
    ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'T_BANK' AFTER tbank_terminal_key,
    ADD COLUMN payment_profile_id BIGINT NULL AFTER provider,
    ADD COLUMN provider_order_id VARCHAR(64) NULL AFTER payment_profile_id,
    ADD COLUMN provider_payment_id VARCHAR(64) NULL AFTER provider_order_id,
    ADD COLUMN provider_merchant_id VARCHAR(64) NULL AFTER provider_payment_id,
    ADD COLUMN provider_payment_mode VARCHAR(32) NULL AFTER provider_merchant_id,
    ADD COLUMN provider_test_mode BOOLEAN NULL AFTER provider_payment_mode,
    ADD COLUMN provider_status VARCHAR(32) NULL AFTER provider_test_mode,
    ADD COLUMN provider_payment_url VARCHAR(1024) NULL AFTER provider_status,
    ADD COLUMN provider_expires_at DATETIME(6) NULL AFTER provider_payment_url;

UPDATE common_invoice_payment_refs payment_ref
LEFT JOIN payment_profiles profile
  ON profile.provider = 'T_BANK'
 AND NULLIF(TRIM(payment_ref.tbank_terminal_key), '') = NULLIF(TRIM(profile.terminal_key), '')
SET payment_ref.provider = 'T_BANK',
    payment_ref.payment_profile_id = COALESCE(payment_ref.payment_profile_id, profile.id),
    payment_ref.provider_order_id = COALESCE(
        NULLIF(TRIM(payment_ref.provider_order_id), ''),
        NULLIF(TRIM(payment_ref.tbank_order_id), '')
    ),
    payment_ref.provider_payment_id = COALESCE(
        NULLIF(TRIM(payment_ref.provider_payment_id), ''),
        NULLIF(TRIM(payment_ref.tbank_payment_id), '')
    ),
    payment_ref.provider_merchant_id = COALESCE(
        NULLIF(TRIM(payment_ref.provider_merchant_id), ''),
        NULLIF(TRIM(payment_ref.tbank_terminal_key), '')
    ),
    payment_ref.provider_status = COALESCE(
        NULLIF(TRIM(payment_ref.provider_status), ''),
        NULLIF(TRIM(payment_ref.status), '')
    );

UPDATE archive_common_invoice_payment_refs payment_ref
LEFT JOIN payment_profiles profile
  ON profile.provider = 'T_BANK'
 AND NULLIF(TRIM(payment_ref.tbank_terminal_key), '') = NULLIF(TRIM(profile.terminal_key), '')
SET payment_ref.provider = 'T_BANK',
    payment_ref.payment_profile_id = COALESCE(payment_ref.payment_profile_id, profile.id),
    payment_ref.provider_order_id = COALESCE(
        NULLIF(TRIM(payment_ref.provider_order_id), ''),
        NULLIF(TRIM(payment_ref.tbank_order_id), '')
    ),
    payment_ref.provider_payment_id = COALESCE(
        NULLIF(TRIM(payment_ref.provider_payment_id), ''),
        NULLIF(TRIM(payment_ref.tbank_payment_id), '')
    ),
    payment_ref.provider_merchant_id = COALESCE(
        NULLIF(TRIM(payment_ref.provider_merchant_id), ''),
        NULLIF(TRIM(payment_ref.tbank_terminal_key), '')
    ),
    payment_ref.provider_status = COALESCE(
        NULLIF(TRIM(payment_ref.provider_status), ''),
        NULLIF(TRIM(payment_ref.status), '')
    );

ALTER TABLE common_invoice_payment_refs
    ADD UNIQUE KEY uk_common_invoice_payment_ref_provider_order (provider, provider_order_id),
    ADD UNIQUE KEY uk_common_invoice_payment_ref_provider_payment (provider, provider_payment_id),
    ADD INDEX idx_common_invoice_payment_ref_provider_status (provider, status, updated_at),
    ADD INDEX idx_common_invoice_payment_ref_profile (payment_profile_id, payment_ref_id);
