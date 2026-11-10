CREATE TABLE manual_receipt_corrections (
    original_id BIGINT NOT NULL PRIMARY KEY,
    replacement_id BIGINT NULL UNIQUE,
    request_key VARCHAR(80) NOT NULL,
    action VARCHAR(16) NOT NULL,
    recipient_profile_id BIGINT NULL,
    actor VARCHAR(150) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    corrected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (original_id) REFERENCES contractor_actual_payment_attributions(id),
    FOREIGN KEY (replacement_id) REFERENCES contractor_actual_payment_attributions(id)
) ENGINE=InnoDB;
