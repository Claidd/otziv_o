-- VIRTUAL keeps column addition instant on MySQL 8.4; index construction is a
-- separate online DDL in the following atomic Flyway version.
ALTER TABLE payment_links
    ADD COLUMN token_hash BINARY(32)
        GENERATED ALWAYS AS (UNHEX(SHA2(token, 256))) VIRTUAL,
    ALGORITHM=INSTANT;
