-- This is deliberately independent of the current status value so existing
-- CHECKING rows written by the old runtime remain valid with a NULL lease tuple.
ALTER TABLE review_external_checks
    ADD CONSTRAINT ck_review_external_checks_processing_lease
        CHECK (
            (
                processing_token IS NULL
                AND processing_owner IS NULL
                AND processing_started_at IS NULL
                AND processing_lease_until IS NULL
            )
            OR (
                processing_token IS NOT NULL
                AND processing_owner IS NOT NULL
                AND processing_started_at IS NOT NULL
                AND processing_lease_until IS NOT NULL
                AND processing_lease_until > processing_started_at
            )
        );
