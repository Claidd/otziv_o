-- Contractor recipient PII has one source of truth: encrypted snapshots in
-- contractor_payment_allocations. Legacy route columns remain available for
-- owner/manual routes, but must be empty for CONTRACTOR_PAYMENT_PROFILE.

UPDATE payment_links
SET manual_phone = NULL,
    manual_recipient_name = NULL,
    manual_comment = NULL
WHERE manual_source = 'CONTRACTOR_PAYMENT_PROFILE';

UPDATE archive_payment_links
SET manual_phone = NULL,
    manual_recipient_name = NULL,
    manual_comment = NULL
WHERE manual_source = 'CONTRACTOR_PAYMENT_PROFILE';

UPDATE common_invoices
SET payment_route_manual_phone = NULL,
    payment_route_manual_recipient = NULL,
    payment_route_manual_comment = NULL,
    payment_route_instruction_text = NULL
WHERE payment_route_manual_source = 'CONTRACTOR_PAYMENT_PROFILE';

UPDATE archive_common_invoices
SET payment_route_manual_phone = NULL,
    payment_route_manual_recipient = NULL,
    payment_route_manual_comment = NULL,
    payment_route_instruction_text = NULL
WHERE payment_route_manual_source = 'CONTRACTOR_PAYMENT_PROFILE';

ALTER TABLE payment_links
    ADD CONSTRAINT ck_payment_links_contractor_pii_blank CHECK (
        COALESCE(manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
        OR (
            COALESCE(TRIM(manual_phone), '') = ''
            AND COALESCE(TRIM(manual_recipient_name), '') = ''
            AND COALESCE(TRIM(manual_comment), '') = ''
        )
    );

ALTER TABLE archive_payment_links
    ADD CONSTRAINT ck_archive_payment_links_contractor_pii_blank CHECK (
        COALESCE(manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
        OR (
            COALESCE(TRIM(manual_phone), '') = ''
            AND COALESCE(TRIM(manual_recipient_name), '') = ''
            AND COALESCE(TRIM(manual_comment), '') = ''
        )
    );

ALTER TABLE common_invoices
    ADD CONSTRAINT ck_common_invoices_contractor_pii_blank CHECK (
        COALESCE(payment_route_manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
        OR (
            COALESCE(TRIM(payment_route_manual_phone), '') = ''
            AND COALESCE(TRIM(payment_route_manual_recipient), '') = ''
            AND COALESCE(TRIM(payment_route_manual_comment), '') = ''
            AND COALESCE(TRIM(payment_route_instruction_text), '') = ''
        )
    );

ALTER TABLE archive_common_invoices
    ADD CONSTRAINT ck_archive_common_invoices_contractor_pii_blank CHECK (
        COALESCE(payment_route_manual_source, '') <> 'CONTRACTOR_PAYMENT_PROFILE'
        OR (
            COALESCE(TRIM(payment_route_manual_phone), '') = ''
            AND COALESCE(TRIM(payment_route_manual_recipient), '') = ''
            AND COALESCE(TRIM(payment_route_manual_comment), '') = ''
            AND COALESCE(TRIM(payment_route_instruction_text), '') = ''
        )
    );
