-- Retain all frozen operation identities. Earlier attempted rows have no
-- trustworthy pre-dispatch marker; only receipt reconciliation may resume them.
ALTER TABLE whatsapp_inbound_reply_outbox
    ADD COLUMN claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN error_code VARCHAR(96) NULL;

UPDATE whatsapp_inbound_reply_outbox
SET state='UNKNOWN', lease_until=NULL, error_code='legacy_attempt_requires_receipt'
WHERE state='PROCESSING' OR (state='PENDING' AND attempts>0);
