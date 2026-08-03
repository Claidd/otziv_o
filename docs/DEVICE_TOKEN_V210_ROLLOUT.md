# V210 device-token rollout and rollback

Migration `V1_10_210__secure_device_tokens.sql` replaces every stored raw
device-token primary key with its lowercase SHA-256 digest and adds a bounded
expiry time. Browsers continue sending the original cookie; the new backend
hashes that value before lookup. A backend built before V210 cannot look up the
digested rows.

## Required rollout sequence

1. Create and verify an encrypted database backup. Record the release commit,
   backup object checksum, row count in `device_tokens`, and UTC timestamp.
2. Confirm that no separately managed or manually scaled old backend replica
   can reconnect during the deployment. V210 must never run while an old
   binary remains able to serve traffic.
3. Deploy with the normal production script. Its app service recreation stops
   the previous service container before the replacement starts and Flyway
   applies V210.
4. Do not start an older app image after V210, including as an emergency
   sidecar or temporary replica.
5. Verify only aggregate metadata; never print token values:

```sql
SELECT COUNT(*) AS token_rows,
       SUM(token REGEXP '^[0-9a-f]{64}$') AS digested_rows,
       SUM(expires_at IS NULL) AS missing_expiry_rows
FROM device_tokens;

SELECT version, success
FROM flyway_schema_history
WHERE version = '1.10.210';
```

Success requires `digested_rows = token_rows`, `missing_expiry_rows = 0`, and
one successful Flyway row. Check authenticated mobile/operator access and the
normal public review-check and payment capabilities after deployment.

## Rollback boundary

An application-only rollback to a pre-V210 image is unsafe. The hash is
one-way, so removing the migration row or column cannot recreate the original
database keys.

Prefer a forward fix using a V210-compatible backend. If an old release is the
only recoverable option, enter maintenance mode, stop every app replica, and
restore both the pre-V210 database backup and the matching old application
release as one coordinated recovery. This discards writes made after that
backup and therefore requires explicit incident-owner approval and business
reconciliation. Never attempt to “unhash” tokens or use `flyway repair` to hide
the migration.

If device sessions can be invalidated during the incident, a safer alternative
is to keep the V210-compatible schema, delete/revoke affected device-token rows
through an approved forward change, and require users to authenticate again.
