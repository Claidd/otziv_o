ALTER TABLE owner_manual_card_payment_approvals
    ADD COLUMN revoked_at DATETIME(6) NULL,
    ADD COLUMN revoked_by VARCHAR(150) NULL,
    ADD COLUMN revocation_reason VARCHAR(500) NULL;
