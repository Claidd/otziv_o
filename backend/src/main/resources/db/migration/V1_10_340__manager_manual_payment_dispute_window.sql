ALTER TABLE specialist_manual_payment_reviews
    MODIFY recipient_profile_id BIGINT NULL,
    MODIFY recipient_user_id BIGINT NULL,
    ADD COLUMN recipient_type VARCHAR(32) NOT NULL DEFAULT 'SPECIALIST',
    ADD COLUMN credit_claimed_at DATETIME(6) NULL,
    ADD COLUMN credited_at DATETIME(6) NULL;
