ALTER TABLE paper_invoice_document
 ADD COLUMN paper_next_reminder_at DATETIME(6) NULL,
 ADD COLUMN paper_reminder_count INT NOT NULL DEFAULT 0,
 ADD COLUMN paper_reminder_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 ADD COLUMN paper_reminder_token CHAR(36) NULL,
 ADD COLUMN paper_reminder_attempt_at DATETIME(6) NULL;
