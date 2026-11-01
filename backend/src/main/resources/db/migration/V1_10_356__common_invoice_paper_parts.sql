CREATE TABLE common_invoice_paper_parts (
 part_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 invoice_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 amount_kopecks BIGINT NOT NULL,
 composition_hash CHAR(64) NOT NULL,
 document_id CHAR(36) NULL,
 delivered_at DATETIME(6) NULL,
 paid_at DATETIME(6) NULL,
 cancelled_at DATETIME(6) NULL,
 automation_hold BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_common_paper_request(invoice_id,request_key),
 KEY idx_common_paper_parent(invoice_id,part_id)
);
ALTER TABLE paper_invoice_document ADD COLUMN parent_invoice_id BIGINT NULL,
 ADD KEY idx_paper_document_parent(parent_invoice_id,created_at);
CREATE TABLE common_invoice_paper_part_orders (
 part_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 company_id BIGINT NOT NULL,
 company_title VARCHAR(300) NOT NULL,
 amount_kopecks BIGINT NOT NULL,
 released_at DATETIME(6) NULL,
 active_order_id BIGINT GENERATED ALWAYS AS (IF(released_at IS NULL,order_id,NULL)) STORED,
 PRIMARY KEY(part_id,order_id),
 UNIQUE KEY uk_paper_part_active_order(active_order_id),
 CONSTRAINT fk_paper_part_order_part FOREIGN KEY(part_id) REFERENCES common_invoice_paper_parts(part_id)
);
