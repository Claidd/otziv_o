ALTER TABLE common_invoices
    ADD COLUMN tbank_payment_created_at DATETIME(6) NULL AFTER tbank_payment_amount_kopecks;
