CREATE TABLE bot_import_lock (
    lock_id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO bot_import_lock (lock_id) VALUES (1);

-- No foreign key: deleting an account must not erase evidence of its previous import.
CREATE TABLE bot_import_origins (
    normalized_login VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    bot_id BIGINT NULL,
    first_imported_at DATETIME(6) NULL,
    source_file VARCHAR(255) NULL,
    source_row INT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Only the explicit initial-import audit event establishes a historical date.
-- File names were not recorded; leave them NULL instead of inventing a source.
INSERT INTO bot_import_origins (normalized_login, bot_id, first_imported_at)
SELECT originals.normalized_login, originals.bot_id, MIN(a.created_at)
FROM (
    SELECT LOWER(TRIM(bot_login)) COLLATE utf8mb4_bin AS normalized_login, MIN(bot_id) AS bot_id
    FROM bots
    WHERE bot_login IS NOT NULL AND TRIM(bot_login) <> ''
    GROUP BY LOWER(TRIM(bot_login)) COLLATE utf8mb4_bin
) originals
LEFT JOIN business_audit_events a
    ON a.entity_type = 'bot' AND a.entity_id = CAST(originals.bot_id AS CHAR)
    AND a.action = 'bot_active_changed'
    AND a.details = 'bot import initial active value'
GROUP BY originals.normalized_login, originals.bot_id;
