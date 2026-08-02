-- A short durable reservation separates provider I/O from database locks.
-- Expired reservations are deliberately quarantined for reconciliation: after
-- an interrupted HTTP exchange we cannot safely assume that no bank payment
-- was created.
ALTER TABLE payment_links
    ADD COLUMN bank_init_nonce VARCHAR(36) NULL,
    ADD COLUMN bank_init_lease_until DATETIME(6) NULL,
    ADD INDEX idx_payment_links_bank_init_lease (bank_init_lease_until, id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
