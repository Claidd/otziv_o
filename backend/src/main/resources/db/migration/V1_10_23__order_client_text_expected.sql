ALTER TABLE orders
    ADD COLUMN order_client_text_expected BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE orders
SET order_client_text_expected = TRUE
WHERE order_waiting_for_client = TRUE;
