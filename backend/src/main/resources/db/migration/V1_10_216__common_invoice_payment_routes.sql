ALTER TABLE common_invoices
    ADD COLUMN payment_route_type VARCHAR(32) NULL AFTER payment_method,
    ADD COLUMN payment_route_profile_id BIGINT NULL AFTER payment_route_type,
    ADD COLUMN payment_route_profile_code VARCHAR(64) NULL AFTER payment_route_profile_id,
    ADD COLUMN payment_route_profile_name VARCHAR(120) NULL AFTER payment_route_profile_code,
    ADD COLUMN payment_route_terminal_key VARCHAR(64) NULL AFTER payment_route_profile_name,
    ADD COLUMN payment_route_manual_source VARCHAR(32) NULL AFTER payment_route_terminal_key,
    ADD COLUMN payment_route_manual_task_id BIGINT NULL AFTER payment_route_manual_source,
    ADD COLUMN payment_route_manual_type VARCHAR(32) NULL AFTER payment_route_manual_task_id,
    ADD COLUMN payment_route_manual_phone VARCHAR(32) NULL AFTER payment_route_manual_type,
    ADD COLUMN payment_route_manual_recipient VARCHAR(160) NULL AFTER payment_route_manual_phone,
    ADD COLUMN payment_route_manual_url VARCHAR(512) NULL AFTER payment_route_manual_recipient,
    ADD COLUMN payment_route_manual_button VARCHAR(80) NULL AFTER payment_route_manual_url,
    ADD COLUMN payment_route_manual_comment VARCHAR(255) NULL AFTER payment_route_manual_button,
    ADD COLUMN payment_route_instruction_text VARCHAR(1000) NULL AFTER payment_route_manual_comment,
    ADD COLUMN payment_route_amount_kopecks BIGINT NULL AFTER payment_route_instruction_text,
    ADD COLUMN payment_route_selected_at DATETIME(6) NULL AFTER payment_route_amount_kopecks,
    ADD INDEX idx_common_invoices_payment_route_task (payment_route_manual_task_id),
    ADD INDEX idx_common_invoices_payment_route_profile_period (payment_route_profile_id, payment_route_selected_at),
    ADD CONSTRAINT fk_common_invoices_payment_route_profile
        FOREIGN KEY (payment_route_profile_id) REFERENCES payment_profiles (id),
    ADD CONSTRAINT fk_common_invoices_payment_route_task
        FOREIGN KEY (payment_route_manual_task_id) REFERENCES manual_payment_tasks (id);
