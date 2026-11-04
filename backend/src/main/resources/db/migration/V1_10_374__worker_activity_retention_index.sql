-- Retention cleanup deletes by created_at; no index started with it, so every nightly run
-- scanned the whole table. InnoDB adds a secondary index online.
ALTER TABLE worker_activity_events
    ADD INDEX idx_worker_activity_events_created (created_at);
