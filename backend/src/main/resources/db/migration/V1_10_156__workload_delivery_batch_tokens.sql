-- One claim token intentionally identifies every row in the same bounded
-- delivery batch.  It therefore must be indexed, but cannot be unique.
ALTER TABLE workload_transfer_offers
    DROP INDEX uk_workload_transfer_offer_processing_token,
    ADD INDEX idx_workload_transfer_offer_processing_token (
        processing_token
    );

ALTER TABLE workload_transfer_emergency_assignments
    DROP INDEX uk_workload_transfer_emergency_notification_token,
    ADD INDEX idx_workload_transfer_emergency_notification_token (
        notification_processing_token
    );
