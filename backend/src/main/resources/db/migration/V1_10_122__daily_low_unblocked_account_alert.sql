ALTER TABLE review_account_pool_alert_state
    ADD COLUMN last_low_unblocked_notified_on DATE NULL AFTER last_notified_at;
