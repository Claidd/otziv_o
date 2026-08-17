CREATE TABLE contractor_legacy_reward_reconciliation_runs (
    reconciliation_run_id BIGINT NOT NULL AUTO_INCREMENT,
    reconciliation_start_date DATE NOT NULL,
    reconciliation_status VARCHAR(24) NOT NULL,
    reconciliation_snapshot_hash CHAR(64) NOT NULL,
    reconciliation_auto_order_count INT NOT NULL DEFAULT 0,
    reconciliation_auto_row_count INT NOT NULL DEFAULT 0,
    reconciliation_manual_order_count INT NOT NULL DEFAULT 0,
    reconciliation_manual_row_count INT NOT NULL DEFAULT 0,
    reconciliation_created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reconciliation_expires_at DATETIME(6) NOT NULL,
    reconciliation_created_by VARCHAR(150) NOT NULL,
    reconciliation_auto_applied_at DATETIME(6) NULL,
    reconciliation_auto_applied_by VARCHAR(150) NULL,
    reconciliation_auto_reason VARCHAR(1000) NULL,
    reconciliation_row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (reconciliation_run_id),
    INDEX idx_contractor_legacy_reconciliation_status
        (reconciliation_status, reconciliation_expires_at)
) ENGINE=InnoDB;

CREATE TABLE contractor_legacy_reward_reconciliation_items (
    reconciliation_item_id BIGINT NOT NULL AUTO_INCREMENT,
    reconciliation_run_id BIGINT NOT NULL,
    reconciliation_order_id BIGINT NOT NULL,
    reconciliation_zp_id BIGINT NOT NULL,
    reconciliation_kind VARCHAR(16) NOT NULL,
    reconciliation_status VARCHAR(16) NOT NULL,
    reconciliation_evidence_category VARCHAR(48) NOT NULL,
    reconciliation_group_hash CHAR(64) NOT NULL,
    original_zp_user BIGINT NOT NULL,
    original_zp_profession BIGINT NOT NULL,
    original_zp_sum DECIMAL(19,2) NULL,
    original_zp_amount INT NOT NULL,
    original_zp_date DATE NULL,
    original_zp_updated_at DATETIME(6) NULL,
    original_zp_active TINYINT(1) NOT NULL,
    original_zp_source VARCHAR(64) NULL,
    original_zp_contractor_role VARCHAR(24) NULL,
    original_zp_attribution_final TINYINT(1) NOT NULL,
    original_zp_reward_basis DECIMAL(19,2) NULL,
    original_zp_attribution_snapshot_hash CHAR(64) NULL,
    target_zp_source VARCHAR(64) NULL,
    target_zp_contractor_role VARCHAR(24) NULL,
    target_zp_attribution_final TINYINT(1) NOT NULL DEFAULT 1,
    manual_completed_on DATE NULL,
    manual_evidence_reference VARCHAR(500) NULL,
    resolution_reason VARCHAR(1000) NULL,
    resolved_at DATETIME(6) NULL,
    resolved_by VARCHAR(150) NULL,
    PRIMARY KEY (reconciliation_item_id),
    UNIQUE KEY uk_contractor_legacy_reconciliation_run_zp
        (reconciliation_run_id, reconciliation_zp_id),
    INDEX idx_contractor_legacy_reconciliation_manual
        (reconciliation_run_id, reconciliation_kind, reconciliation_status, reconciliation_order_id),
    INDEX idx_contractor_legacy_reconciliation_attestation
        (reconciliation_order_id, reconciliation_kind, reconciliation_status, manual_completed_on),
    CONSTRAINT fk_contractor_legacy_reconciliation_item_run
        FOREIGN KEY (reconciliation_run_id)
        REFERENCES contractor_legacy_reward_reconciliation_runs (reconciliation_run_id)
        ON DELETE RESTRICT
) ENGINE=InnoDB;
