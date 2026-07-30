-- A single token identifies every offer inserted by one bounded staging call.
-- It prevents a follow-up multi-table UPDATE from accidentally binding READY
-- offers created by another application instance at the same timestamp.
ALTER TABLE workload_transfer_offers
    ADD COLUMN staging_batch_token CHAR(36) NULL
        AFTER workflow_version,
    ADD INDEX idx_workload_transfer_offer_staging_batch (
        staging_batch_token,
        status,
        workload_transfer_offer_id
    );
