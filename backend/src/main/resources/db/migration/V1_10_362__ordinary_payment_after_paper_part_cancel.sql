ALTER TABLE common_invoice_orders
    ADD COLUMN ordinary_after_paper_cancel BOOLEAN NOT NULL DEFAULT FALSE;
