-- A persisted rollout boundary keeps recovery workers from importing old
-- payment instructions that existed before contractor routing was introduced.
INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES (
    'contractor-payments.shadow-backfill-started-at',
    DATE_FORMAT(CURRENT_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%f'),
    CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_payment_links_contractor_shadow_backfill
    ON payment_links (created_at, id);

CREATE INDEX idx_common_invoices_contractor_shadow_backfill
    ON common_invoices (payment_route_selected_at, invoice_id);

ALTER TABLE contractor_payment_allocations
    ADD COLUMN payment_comment_snapshot VARCHAR(255) NULL AFTER bank_name_snapshot;

-- SHADOW eligibility and production eligibility are deliberately separate.
-- Existing test profiles stay visible in SHADOW but no profile can receive a
-- real client route until it is explicitly admitted to the canary.
ALTER TABLE contractor_payment_profiles
    ADD COLUMN live_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;

ALTER TABLE payment_links
    ADD COLUMN contractor_allocation_id BIGINT NULL AFTER payment_profile_name,
    ADD COLUMN manual_bank_name VARCHAR(120) NULL AFTER manual_recipient_name,
    ADD CONSTRAINT fk_payment_links_contractor_allocation
        FOREIGN KEY (contractor_allocation_id) REFERENCES contractor_payment_allocations (id),
    ADD UNIQUE INDEX uk_payment_links_contractor_allocation (contractor_allocation_id);

-- Archive rows keep the immutable recipient snapshot shown in the payment
-- journal after the live link has been moved out of payment_links.
ALTER TABLE archive_payment_links
    ADD COLUMN contractor_allocation_id BIGINT NULL AFTER payment_profile_name,
    ADD COLUMN manual_bank_name VARCHAR(120) NULL AFTER manual_recipient_name,
    ADD INDEX idx_archive_payment_links_contractor_allocation (contractor_allocation_id);

ALTER TABLE common_invoices
    ADD COLUMN contractor_allocation_id BIGINT NULL AFTER payment_route_profile_name,
    ADD COLUMN payment_route_manual_bank_name VARCHAR(120) NULL AFTER payment_route_manual_recipient,
    ADD COLUMN client_reported_at DATETIME(6) NULL AFTER payment_route_selected_at,
    ADD CONSTRAINT fk_common_invoices_contractor_allocation
        FOREIGN KEY (contractor_allocation_id) REFERENCES contractor_payment_allocations (id),
    ADD UNIQUE INDEX uk_common_invoices_contractor_allocation (contractor_allocation_id);

-- archive_common_invoices was cloned in V133, before the immutable payment
-- route columns from V216/V219 existed. Mirror every snapshot field without
-- live foreign keys/unique constraints so dynamic archive/restore retains the
-- exact recipient instructions the client saw.
ALTER TABLE archive_common_invoices
    ADD COLUMN payment_route_type VARCHAR(32) NULL AFTER payment_method,
    ADD COLUMN payment_route_profile_id BIGINT NULL AFTER payment_route_type,
    ADD COLUMN payment_route_profile_code VARCHAR(64) NULL AFTER payment_route_profile_id,
    ADD COLUMN payment_route_profile_name VARCHAR(120) NULL AFTER payment_route_profile_code,
    ADD COLUMN contractor_allocation_id BIGINT NULL AFTER payment_route_profile_name,
    ADD COLUMN payment_route_terminal_key VARCHAR(64) NULL AFTER contractor_allocation_id,
    ADD COLUMN payment_route_manual_source VARCHAR(32) NULL AFTER payment_route_terminal_key,
    ADD COLUMN payment_route_manual_task_id BIGINT NULL AFTER payment_route_manual_source,
    ADD COLUMN payment_route_manual_type VARCHAR(32) NULL AFTER payment_route_manual_task_id,
    ADD COLUMN payment_route_manual_phone VARCHAR(32) NULL AFTER payment_route_manual_type,
    ADD COLUMN payment_route_manual_recipient VARCHAR(160) NULL AFTER payment_route_manual_phone,
    ADD COLUMN payment_route_manual_bank_name VARCHAR(120) NULL AFTER payment_route_manual_recipient,
    ADD COLUMN payment_route_manual_url VARCHAR(512) NULL AFTER payment_route_manual_bank_name,
    ADD COLUMN payment_route_manual_button VARCHAR(80) NULL AFTER payment_route_manual_url,
    ADD COLUMN payment_route_manual_comment VARCHAR(255) NULL AFTER payment_route_manual_button,
    ADD COLUMN payment_route_instruction_text VARCHAR(1000) NULL AFTER payment_route_manual_comment,
    ADD COLUMN payment_route_amount_kopecks BIGINT NULL AFTER payment_route_instruction_text,
    ADD COLUMN payment_route_selected_at DATETIME(6) NULL AFTER payment_route_amount_kopecks,
    ADD COLUMN client_reported_at DATETIME(6) NULL AFTER payment_route_selected_at,
    ADD INDEX idx_archive_common_invoices_contractor_allocation (contractor_allocation_id);

ALTER TABLE specialist_transfer_audit
    ADD COLUMN review_recovery_task_count INT NOT NULL DEFAULT 0 AFTER bad_review_task_count;
