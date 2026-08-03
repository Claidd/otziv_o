-- New device tokens persist only a SHA-256 digest. Existing UUID bearer values
-- are transformed in place, so clients keep using the same cookie while the
-- application looks the row up by its digest.
-- Rollout requirement: stop or upgrade every old application replica before
-- this migration runs; old binaries only know plaintext primary-key lookup.
ALTER TABLE device_tokens
    ADD COLUMN expires_at TIMESTAMP(6) NULL AFTER created_at;

-- Give already issued cookies a bounded grace period instead of invalidating
-- every operator device at deployment time.
UPDATE device_tokens
SET expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY)
WHERE expires_at IS NULL;

UPDATE device_tokens
SET token = LOWER(SHA2(token, 256))
WHERE token NOT REGEXP '^[0-9A-Fa-f]{64}$';

UPDATE device_tokens
SET token = LOWER(token)
WHERE token REGEXP '^[0-9A-Fa-f]{64}$';

ALTER TABLE device_tokens
    MODIFY COLUMN token VARCHAR(64)
        CHARACTER SET ascii
        COLLATE ascii_bin
        NOT NULL;
