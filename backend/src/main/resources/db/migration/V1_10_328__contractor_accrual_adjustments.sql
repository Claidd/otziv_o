-- Manual bonuses and deductions are independent of order rewards and the
-- one-time opening balance. Corrections are append-only reversal entries.
CREATE TABLE contractor_accrual_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    profile_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    bucket VARCHAR(16) NOT NULL,
    signed_amount_kopecks BIGINT NOT NULL,
    effective_on DATE NOT NULL,
    reference_month DATE NOT NULL,
    reason VARCHAR(255) NOT NULL,
    evidence_reference VARCHAR(160) NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    reversal_of_id BIGINT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_contractor_accrual_adjustment_profile
        FOREIGN KEY (profile_id) REFERENCES contractor_payment_profiles (id),
    CONSTRAINT fk_contractor_accrual_adjustment_reversal
        FOREIGN KEY (reversal_of_id) REFERENCES contractor_accrual_adjustments (id),
    CONSTRAINT uk_contractor_accrual_adjustment_idempotency
        UNIQUE (profile_id, idempotency_key),
    CONSTRAINT uk_contractor_accrual_adjustment_reversal
        UNIQUE (reversal_of_id),
    INDEX idx_contractor_accrual_adjustment_profile_date
        (profile_id, effective_on),
    INDEX idx_contractor_accrual_adjustment_date_profile
        (effective_on, profile_id)
);
