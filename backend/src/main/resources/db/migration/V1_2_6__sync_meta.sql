-- Добавляем колонку для хранения идентификатора группы WhatsApp
ALTER TABLE managers
    ADD COLUMN group_id VARCHAR(255) DEFAULT NULL;

CREATE TABLE sync_metadata (
                               id VARCHAR(255) PRIMARY KEY,
                               last_sync TIMESTAMP
);

INSERT INTO sync_metadata (id, last_sync) VALUES ('lead_sync', '2025-05-24 00:00:00');





