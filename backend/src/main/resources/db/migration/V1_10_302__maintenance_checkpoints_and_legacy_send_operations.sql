-- The offline performer upgrade tool can create these identical additive tables
-- before V288. It never creates or modifies Flyway's history/checksums.
CREATE TABLE IF NOT EXISTS performer_legacy_maintenance_runs (
    run_id CHAR(36) NOT NULL PRIMARY KEY,
    phase VARCHAR(24) NOT NULL,
    before_v288 BOOLEAN NOT NULL,
    assignment_upper BIGINT NOT NULL,
    offer_upper BIGINT NOT NULL,
    assignment_cursor BIGINT NOT NULL DEFAULT 0,
    offer_cursor BIGINT NOT NULL DEFAULT 0,
    scanned BIGINT NOT NULL DEFAULT 0,
    changed BIGINT NOT NULL DEFAULT 0,
    actor VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_slot TINYINT GENERATED ALWAYS AS
        (CASE WHEN phase NOT IN ('COMPLETE','ABORTED') THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uq_performer_maintenance_active(active_slot)
) ENGINE=InnoDB;


CREATE TABLE IF NOT EXISTS performer_legacy_maintenance_rows (
    run_id CHAR(36) NOT NULL,
    entity_type VARCHAR(16) NOT NULL,
    entity_id BIGINT NOT NULL,
    original_status VARCHAR(32) NULL,
    original_message_id INT NULL,
    original_generation BIGINT NULL,
    restored BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(run_id,entity_type,entity_id),
    KEY ix_performer_maintenance_restore(run_id,restored,entity_type,entity_id)
) ENGINE=InnoDB;

-- END_PERFORMER_MAINTENANCE_SCHEMA

CREATE TABLE whatsapp_business_send_operations (
    operation_id VARCHAR(128) NOT NULL PRIMARY KEY,
    envelope_hash CHAR(64) NOT NULL,
    envelope_ciphertext MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE whatsapp_manual_send_operations (
    operation_id CHAR(36) NOT NULL PRIMARY KEY,
    actor_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

ALTER TABLE leads ADD COLUMN whatsapp_work_generation BIGINT NOT NULL DEFAULT 0;
