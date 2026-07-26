CREATE INDEX idx_scheduled_message_manager_control_order
    ON scheduled_client_message_state (state_status, order_id, last_attempt_at, state_id);

CREATE INDEX idx_scheduled_message_manager_control_company
    ON scheduled_client_message_state (state_status, company_id, last_attempt_at, state_id);

CREATE INDEX idx_common_invoice_manager_control_status_updated
    ON common_invoices (status, updated_at, invoice_id);
