ALTER TABLE payment_profiles
    ADD COLUMN manual_comment VARCHAR(255) NULL AFTER manual_payment_button_label;
