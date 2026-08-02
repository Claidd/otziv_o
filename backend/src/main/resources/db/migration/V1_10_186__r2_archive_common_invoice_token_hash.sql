ALTER TABLE archive_common_invoices
    ADD COLUMN token_hash BINARY(32)
        GENERATED ALWAYS AS (UNHEX(SHA2(token, 256))) VIRTUAL,
    ALGORITHM=INSTANT;
