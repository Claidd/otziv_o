-- Historical payment/common-invoice tables and the newly-created contractor
-- table can inherit different database defaults (utf8mb4_0900_ai_ci or
-- utf8mb4_unicode_ci). Native queue/archive queries compare these generation
-- columns directly, so normalize both sides instead of relying on either
-- installation's default collation.
ALTER TABLE contractor_payment_allocations
    MODIFY COLUMN source_generation_snapshot VARCHAR(36)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;

ALTER TABLE payment_links
    MODIFY COLUMN shadow_route_generation VARCHAR(36)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;

ALTER TABLE archive_payment_links
    MODIFY COLUMN shadow_route_generation VARCHAR(36)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;

ALTER TABLE common_invoices
    MODIFY COLUMN shadow_route_generation VARCHAR(36)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;

ALTER TABLE archive_common_invoices
    MODIFY COLUMN shadow_route_generation VARCHAR(36)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;
