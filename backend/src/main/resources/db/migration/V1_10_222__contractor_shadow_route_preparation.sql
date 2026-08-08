ALTER TABLE payment_links
    ADD COLUMN shadow_route_generation VARCHAR(36) NULL AFTER contractor_allocation_id,
    ADD COLUMN shadow_route_order_id BIGINT NULL AFTER shadow_route_generation,
    ADD COLUMN shadow_route_worker_id BIGINT NULL AFTER shadow_route_order_id,
    ADD COLUMN shadow_route_worker_user_id BIGINT NULL AFTER shadow_route_worker_id,
    ADD COLUMN shadow_route_manager_id BIGINT NULL AFTER shadow_route_worker_user_id,
    ADD COLUMN shadow_route_manager_user_id BIGINT NULL AFTER shadow_route_manager_id,
    ADD COLUMN shadow_route_amount_kopecks BIGINT NULL AFTER shadow_route_manager_user_id,
    ADD COLUMN shadow_route_prepared_at DATETIME(6) NULL AFTER shadow_route_amount_kopecks,
    ADD COLUMN contractor_evidence_original_link_id BIGINT NULL AFTER shadow_route_prepared_at,
    ADD INDEX idx_payment_links_shadow_route_generation (shadow_route_generation);

CREATE INDEX idx_payment_links_contractor_evidence_original
    ON payment_links (contractor_evidence_original_link_id, id);

ALTER TABLE archive_payment_links
    ADD COLUMN shadow_route_generation VARCHAR(36) NULL AFTER contractor_allocation_id,
    ADD COLUMN shadow_route_order_id BIGINT NULL AFTER shadow_route_generation,
    ADD COLUMN shadow_route_worker_id BIGINT NULL AFTER shadow_route_order_id,
    ADD COLUMN shadow_route_worker_user_id BIGINT NULL AFTER shadow_route_worker_id,
    ADD COLUMN shadow_route_manager_id BIGINT NULL AFTER shadow_route_worker_user_id,
    ADD COLUMN shadow_route_manager_user_id BIGINT NULL AFTER shadow_route_manager_id,
    ADD COLUMN shadow_route_amount_kopecks BIGINT NULL AFTER shadow_route_manager_user_id,
    ADD COLUMN shadow_route_prepared_at DATETIME(6) NULL AFTER shadow_route_amount_kopecks,
    ADD COLUMN contractor_evidence_original_link_id BIGINT NULL AFTER shadow_route_prepared_at,
    ADD INDEX idx_archive_payment_links_shadow_route_generation (shadow_route_generation);

CREATE INDEX idx_archive_payment_links_contractor_evidence_original
    ON archive_payment_links (contractor_evidence_original_link_id, id);

ALTER TABLE common_invoices
    ADD COLUMN shadow_route_generation VARCHAR(36) NULL AFTER contractor_allocation_id,
    ADD COLUMN shadow_route_worker_state VARCHAR(24) NULL AFTER shadow_route_generation,
    ADD COLUMN shadow_route_worker_id BIGINT NULL AFTER shadow_route_worker_state,
    ADD COLUMN shadow_route_worker_user_id BIGINT NULL AFTER shadow_route_worker_id,
    ADD COLUMN shadow_route_manager_id BIGINT NULL AFTER shadow_route_worker_user_id,
    ADD COLUMN shadow_route_manager_user_id BIGINT NULL AFTER shadow_route_manager_id,
    ADD COLUMN shadow_route_amount_kopecks BIGINT NULL AFTER shadow_route_manager_user_id,
    ADD COLUMN shadow_route_membership_hash VARCHAR(64) NULL AFTER shadow_route_amount_kopecks,
    ADD COLUMN shadow_route_contractor_eligible BOOLEAN NOT NULL DEFAULT FALSE AFTER shadow_route_membership_hash,
    ADD COLUMN shadow_route_prepared_at DATETIME(6) NULL AFTER shadow_route_contractor_eligible,
    ADD INDEX idx_common_invoices_shadow_route_generation (shadow_route_generation);

ALTER TABLE archive_common_invoices
    ADD COLUMN shadow_route_generation VARCHAR(36) NULL AFTER contractor_allocation_id,
    ADD COLUMN shadow_route_worker_state VARCHAR(24) NULL AFTER shadow_route_generation,
    ADD COLUMN shadow_route_worker_id BIGINT NULL AFTER shadow_route_worker_state,
    ADD COLUMN shadow_route_worker_user_id BIGINT NULL AFTER shadow_route_worker_id,
    ADD COLUMN shadow_route_manager_id BIGINT NULL AFTER shadow_route_worker_user_id,
    ADD COLUMN shadow_route_manager_user_id BIGINT NULL AFTER shadow_route_manager_id,
    ADD COLUMN shadow_route_amount_kopecks BIGINT NULL AFTER shadow_route_manager_user_id,
    ADD COLUMN shadow_route_membership_hash VARCHAR(64) NULL AFTER shadow_route_amount_kopecks,
    ADD COLUMN shadow_route_contractor_eligible BOOLEAN NOT NULL DEFAULT FALSE AFTER shadow_route_membership_hash,
    ADD COLUMN shadow_route_prepared_at DATETIME(6) NULL AFTER shadow_route_contractor_eligible,
    ADD INDEX idx_archive_common_invoices_shadow_route_generation (shadow_route_generation);

ALTER TABLE contractor_payment_allocations
    ADD COLUMN source_generation_snapshot VARCHAR(36) NULL AFTER source_id,
    ADD INDEX idx_contractor_allocations_source_generation
        (mode, source_type, source_id, source_generation_snapshot);

-- Rows written by an older application binary while V219 was already
-- installed do not contain an immutable preparation snapshot and must never
-- be reconstructed from mutable order state.  Persist the exact V222 cutover
-- only after every required column exists; null snapshots before this point
-- are deliberately out of scope, while null snapshots after it are treated as
-- a preparation failure and remain visible to retry/health monitoring.
INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES (
    'contractor-payments.shadow-preparation-started-at',
    DATE_FORMAT(CURRENT_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%f'),
    CURRENT_TIMESTAMP(6)
);
