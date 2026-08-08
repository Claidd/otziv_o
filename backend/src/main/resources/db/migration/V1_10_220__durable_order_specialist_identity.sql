-- An order is durable evidence of who currently owns the card and, together
-- with reviews/tasks, who performed the work. Deleting a worker must never
-- cascade into deleting live orders and their payment/accounting evidence.
ALTER TABLE orders
    DROP FOREIGN KEY order_worker;

-- MySQL validates foreign-key names before applying all clauses of one ALTER,
-- so dropping and recreating the same constraint name in a single statement
-- fails with error 1826. Keep the replacement as a separate DDL statement.
ALTER TABLE orders
    ADD CONSTRAINT order_worker
    FOREIGN KEY (order_worker) REFERENCES workers (worker_id)
    ON DELETE RESTRICT
    ON UPDATE NO ACTION;
