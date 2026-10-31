ALTER TABLE paper_invoice_document
    ADD COLUMN message_delivery VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN message_id VARCHAR(512) NULL;

-- Earlier invoice messages already included the payment instructions as their caption.
UPDATE paper_invoice_document
SET message_delivery='SENT',message_id=invoice_message_id
WHERE invoice_delivery='SENT';
