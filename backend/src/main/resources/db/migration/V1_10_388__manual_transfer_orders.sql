CREATE TABLE manual_transfer_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_profile_id BIGINT NOT NULL,
    bank_reference VARCHAR(100) NOT NULL,
    amount_kopecks BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    receipt_url VARCHAR(1024) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_transfer_reference (recipient_profile_id, bank_reference)
);
CREATE TABLE manual_transfer_orders (
    receipt_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    amount_kopecks BIGINT NOT NULL,
    PRIMARY KEY (receipt_id, order_id),
    UNIQUE KEY uq_transfer_review (review_id),
    FOREIGN KEY (receipt_id) REFERENCES manual_transfer_receipts(id)
);
