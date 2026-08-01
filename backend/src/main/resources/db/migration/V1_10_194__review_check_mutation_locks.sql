-- Order-scoped mutex for every legacy and opaque review-check mutation.
-- There is intentionally no FK: an order moves between live and archive tables
-- while the lock identity must remain stable across that transition.
CREATE TABLE review_check_mutation_locks (
    order_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_id)
) ENGINE=InnoDB;
