ALTER TABLE filial
    ADD COLUMN filial_archived TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN filial_archived_at DATETIME(6) NULL;

ALTER TABLE orders
    DROP FOREIGN KEY order_filial;

ALTER TABLE orders
    ADD CONSTRAINT order_filial
        FOREIGN KEY (order_filial)
        REFERENCES filial (filial_id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE;

CREATE INDEX idx_filial_company_archived
    ON filial (company_id, filial_archived, filial_id);
