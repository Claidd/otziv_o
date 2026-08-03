-- AES-GCM envelopes are larger than their plaintext values. Encryption and
-- key rotation are intentionally performed by the application so encryption
-- keys never enter Flyway history or the database server.
ALTER TABLE telephones
    MODIFY telephone_google_password VARCHAR(1024) NULL,
    MODIFY telephone_avito_password VARCHAR(1024) NULL,
    MODIFY telephone_mail_password VARCHAR(1024) NULL;

ALTER TABLE bots
    MODIFY bot_password VARCHAR(1024) NULL;

ALTER TABLE bad_review_tasks
    MODIFY bad_review_task_bot_password_snapshot VARCHAR(1024) NULL;

ALTER TABLE review_recovery_tasks
    MODIFY review_recovery_task_bot_password_snapshot VARCHAR(1024) NULL;

ALTER TABLE archive_bad_review_tasks
    MODIFY bad_review_task_bot_password_snapshot VARCHAR(1024) NULL;
