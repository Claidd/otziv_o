ALTER TABLE common_invoice_orders
    ADD COLUMN original_order_status_title VARCHAR(64) NULL AFTER amount_kopecks;
