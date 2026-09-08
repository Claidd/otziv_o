-- Older binaries ignore delivery_state and may resend UNKNOWN/QUARANTINED work.
-- Their schema validation must fail instead of silently running that consumer.
-- Drain all old instances before this migration. Keep this table on rollback.
RENAME TABLE lead_sync_queue TO lead_command_queue;
