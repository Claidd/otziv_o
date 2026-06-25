ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN order_details_id VARCHAR(36) NULL AFTER target_url,
    ADD COLUMN chat_url VARCHAR(500) NULL AFTER order_details_id,
    ADD COLUMN follow_up_at DATETIME(6) NULL AFTER chat_url,
    ADD COLUMN last_manual_touch_at DATETIME(6) NULL AFTER follow_up_at,
    ADD INDEX idx_manager_control_concrete_followup (control_id, entity_type, follow_up_at);
