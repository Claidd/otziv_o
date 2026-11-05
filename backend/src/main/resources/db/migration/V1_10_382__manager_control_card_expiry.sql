-- Expiry of stale manager control cards (05.11.2026, owner's clean slate).
-- A daily job closes unresolved cards of control days older than 30 days as RESOLVED with an
-- automatic resolution. expired_at marks that outcome explicitly, so an expiry is never mistaken
-- for a real resolution (for example "the deferred problem was resolved later" in the audit).
-- Additive and instant on MySQL 8; existing rows stay NULL.
ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN expired_at DATETIME(6) NULL;
