ALTER TABLE common_invoice_message_queue
    ADD COLUMN dispatch_started_at DATETIME(6) NULL,
    ADD COLUMN reviewed_at DATETIME(6) NULL,
    ADD COLUMN reviewed_by VARCHAR(160) NULL,
    ADD COLUMN replacement_operation_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE INDEX ux_common_invoice_replacement (replacement_operation_id);
