ALTER TABLE orders
    ADD COLUMN order_waiting_for_client BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_orders_waiting_for_client
    ON orders (order_waiting_for_client);
