-- Cancel is an external side effect: a timeout does not prove that the bank
-- left the payment unchanged. These fields keep an in-flight attempt durable
-- while the local status is quarantined for GetState reconciliation.
ALTER TABLE payment_links
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN bank_cancel_nonce VARCHAR(36) NULL,
    ADD COLUMN bank_cancel_lease_until DATETIME(6) NULL,
    ADD COLUMN bank_cancel_origin_status VARCHAR(32) NULL,
    ADD COLUMN bank_cancel_origin_error VARCHAR(512) NULL,
    ADD INDEX idx_payment_links_bank_cancel_lease (bank_cancel_lease_until, id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
