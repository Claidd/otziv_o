CREATE TABLE contractor_direct_settlements (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    profile_id BIGINT NOT NULL,
    settlement_type VARCHAR(16) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    amount_kopecks BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    evidence_reference VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    actor VARCHAR(150) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    original_settlement_id BIGINT NULL,
    allocation_id BIGINT NULL,
    CONSTRAINT uk_contractor_direct_settlement_idempotency
        UNIQUE (profile_id, idempotency_key),
    CONSTRAINT ck_contractor_direct_settlement_amount
        CHECK (amount_kopecks > 0 AND amount_kopecks <= 100000000000),
    CONSTRAINT ck_contractor_direct_settlement_mode
        CHECK (mode IN ('SHADOW', 'LIVE')),
    CONSTRAINT ck_contractor_direct_settlement_type
        CHECK (
            (settlement_type = 'PAYMENT' AND original_settlement_id IS NULL)
            OR (settlement_type = 'REVERSAL' AND original_settlement_id IS NOT NULL)
        ),
    CONSTRAINT fk_contractor_direct_settlement_profile
        FOREIGN KEY (profile_id) REFERENCES contractor_payment_profiles (id),
    CONSTRAINT fk_contractor_direct_settlement_original
        FOREIGN KEY (original_settlement_id) REFERENCES contractor_direct_settlements (id),
    CONSTRAINT fk_contractor_direct_settlement_allocation
        FOREIGN KEY (allocation_id) REFERENCES contractor_payment_allocations (id),
    INDEX idx_contractor_direct_settlement_profile_time (profile_id, effective_at, id),
    INDEX idx_contractor_direct_settlement_original (original_settlement_id, id),
    INDEX idx_contractor_direct_settlement_allocation (allocation_id)
) ENGINE=InnoDB;
