ALTER TABLE companies
    ADD COLUMN company_contractor_payment_routing_enabled BOOLEAN NOT NULL DEFAULT TRUE
        AFTER company_allow_worker_publication_date_edit;

ALTER TABLE payment_links
    ADD COLUMN shadow_route_company_routing_allowed BOOLEAN NOT NULL DEFAULT TRUE
        AFTER shadow_route_amount_kopecks;

ALTER TABLE archive_payment_links
    ADD COLUMN shadow_route_company_routing_allowed BOOLEAN NOT NULL DEFAULT TRUE
        AFTER shadow_route_amount_kopecks;

ALTER TABLE common_invoices
    ADD COLUMN shadow_route_company_routing_allowed BOOLEAN NOT NULL DEFAULT TRUE
        AFTER shadow_route_contractor_eligible;

ALTER TABLE archive_common_invoices
    ADD COLUMN shadow_route_company_routing_allowed BOOLEAN NOT NULL DEFAULT TRUE
        AFTER shadow_route_contractor_eligible;
