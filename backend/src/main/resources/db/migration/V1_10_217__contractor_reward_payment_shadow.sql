ALTER TABLE zp
    ADD COLUMN zp_contractor_role VARCHAR(24) NULL AFTER zp_source,
    ADD INDEX idx_zp_contractor_sync (zp_user, zp_contractor_role, zp_date, zp_id);

CREATE TABLE contractor_payment_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    contractor_role VARCHAR(24) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    recipient_name VARCHAR(512) NULL,
    payment_phone VARCHAR(512) NULL,
    bank_name VARCHAR(120) NULL,
    payment_comment VARCHAR(255) NULL,
    opening_balance_kopecks BIGINT NOT NULL DEFAULT 0,
    tracking_started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    tracking_start_zp_id BIGINT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_contractor_payment_profiles_user_role UNIQUE (user_id, contractor_role),
    CONSTRAINT fk_contractor_payment_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_contractor_payment_profiles_enabled_role (enabled, contractor_role)
) ENGINE=InnoDB;

CREATE TABLE contractor_reward_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    profile_id BIGINT NOT NULL,
    source_zp_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    amount_kopecks BIGINT NOT NULL,
    work_units INT NOT NULL DEFAULT 0,
    occurred_on DATE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    source_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_contractor_reward_ledger_zp UNIQUE (source_zp_id),
    CONSTRAINT fk_contractor_reward_ledger_profile
        FOREIGN KEY (profile_id) REFERENCES contractor_payment_profiles (id),
    CONSTRAINT fk_contractor_reward_ledger_zp
        FOREIGN KEY (source_zp_id) REFERENCES zp (zp_id),
    INDEX idx_contractor_reward_ledger_profile_active_date (profile_id, active, occurred_on),
    INDEX idx_contractor_reward_ledger_order (order_id)
) ENGINE=InnoDB;

CREATE TABLE contractor_payment_allocations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    mode VARCHAR(16) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    common_invoice_id BIGINT NULL,
    recipient_type VARCHAR(24) NOT NULL,
    recipient_profile_id BIGINT NULL,
    recipient_user_id BIGINT NULL,
    current_worker_id BIGINT NULL,
    current_manager_id BIGINT NULL,
    amount_kopecks BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    recipient_name_snapshot VARCHAR(512) NULL,
    payment_phone_snapshot VARCHAR(512) NULL,
    bank_name_snapshot VARCHAR(120) NULL,
    available_before_kopecks BIGINT NULL,
    reserved_at DATETIME(6) NULL,
    client_reported_at DATETIME(6) NULL,
    confirmed_at DATETIME(6) NULL,
    released_at DATETIME(6) NULL,
    release_reason VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_contractor_payment_allocations_source UNIQUE (mode, source_type, source_id),
    CONSTRAINT fk_contractor_payment_allocations_profile
        FOREIGN KEY (recipient_profile_id) REFERENCES contractor_payment_profiles (id),
    CONSTRAINT fk_contractor_payment_allocations_user
        FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT fk_contractor_payment_allocations_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_contractor_payment_allocations_common_invoice
        FOREIGN KEY (common_invoice_id) REFERENCES common_invoices (invoice_id),
    INDEX idx_contractor_allocations_profile_status (recipient_profile_id, mode, status),
    INDEX idx_contractor_allocations_order_status (order_id, mode, status),
    INDEX idx_contractor_allocations_common_status (common_invoice_id, mode, status)
) ENGINE=InnoDB;

CREATE TABLE contractor_reward_applications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    allocation_id BIGINT NOT NULL,
    ledger_entry_id BIGINT NOT NULL,
    amount_kopecks BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_contractor_reward_applications_pair UNIQUE (allocation_id, ledger_entry_id),
    CONSTRAINT fk_contractor_reward_applications_allocation
        FOREIGN KEY (allocation_id) REFERENCES contractor_payment_allocations (id),
    CONSTRAINT fk_contractor_reward_applications_ledger
        FOREIGN KEY (ledger_entry_id) REFERENCES contractor_reward_ledger (id),
    INDEX idx_contractor_reward_applications_ledger (ledger_entry_id)
) ENGINE=InnoDB;

INSERT INTO contractor_payment_profiles (user_id, contractor_role, enabled, tracking_started_at)
SELECT DISTINCT u.id, 'SPECIALIST', FALSE, CURRENT_TIMESTAMP(6)
FROM users u
JOIN users_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE r.name = 'ROLE_WORKER';

INSERT INTO contractor_payment_profiles (user_id, contractor_role, enabled, tracking_started_at)
SELECT DISTINCT u.id, 'MANAGER', FALSE, CURRENT_TIMESTAMP(6)
FROM users u
JOIN users_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE r.name = 'ROLE_MANAGER';

UPDATE contractor_payment_profiles
SET tracking_start_zp_id = (SELECT COALESCE(MAX(z.zp_id), 0) FROM zp z);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('contractor-payments.shadow-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('contractor-payments.live-routing-enabled', 'false', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = VALUES(updated_at);
