-- A payment notification is sent through an external chat provider and must
-- never be called while a database transaction is held. This separate lease
-- table fences concurrent retry schedulers without changing payment_links or
-- the public payment contract. Rows are removed after a fenced finalization;
-- an abandoned row becomes claimable after its bounded lease expires.
CREATE TABLE payment_success_notification_retry_claims (
    payment_link_id BIGINT NOT NULL,
    processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processing_owner VARCHAR(128) NOT NULL,
    processing_started_at DATETIME(6) NOT NULL,
    processing_lease_until DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (payment_link_id),
    INDEX idx_payment_success_notification_claim_lease
        (processing_lease_until, payment_link_id),
    CONSTRAINT fk_payment_success_notification_claim_link
        FOREIGN KEY (payment_link_id) REFERENCES payment_links (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_payment_success_notification_claim_lease
        CHECK (processing_lease_until > processing_started_at)
) ENGINE=InnoDB;
