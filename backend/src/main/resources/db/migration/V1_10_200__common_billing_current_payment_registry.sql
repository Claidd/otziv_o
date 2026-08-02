-- Make common_invoice_payment_refs the durable registry for the payment that
-- common_invoices currently exposes. Legacy rows may predate that invariant,
-- so unsafe identities are quarantined before the database guard is enabled.

-- Production databases may retain an older schema default collation while the
-- CommonBilling tables use the MySQL 8/9 table collation. Derive temporary
-- string columns from the live table so comparisons cannot fail with an
-- implicit-collation mismatch before unsafe rows are quarantined.
CREATE TEMPORARY TABLE cb_registry_current_bindings ENGINE = InnoDB AS
SELECT
    invoice.invoice_id,
    invoice.status AS invoice_status,
    ref.tbank_order_id,
    ref.tbank_payment_id,
    ref.tbank_terminal_key,
    ref.amount_kopecks,
    invoice.payment_url,
    CAST(FALSE AS UNSIGNED) AS init_in_progress,
    CAST(FALSE AS UNSIGNED) AS init_without_payment_id
FROM common_invoices invoice
JOIN common_invoice_payment_refs ref ON 1 = 0
WHERE 1 = 0;

ALTER TABLE cb_registry_current_bindings
    ADD PRIMARY KEY (invoice_id);

INSERT INTO cb_registry_current_bindings (
    invoice_id,
    invoice_status,
    tbank_order_id,
    tbank_payment_id,
    tbank_terminal_key,
    amount_kopecks,
    payment_url,
    init_in_progress,
    init_without_payment_id
)
SELECT
    invoice.invoice_id,
    invoice.status,
    NULLIF(TRIM(invoice.tbank_order_id), ''),
    NULLIF(TRIM(invoice.tbank_payment_id), ''),
    NULLIF(TRIM(invoice.tbank_terminal_key), ''),
    invoice.tbank_payment_amount_kopecks,
    NULLIF(TRIM(invoice.payment_url), ''),
    CASE
        WHEN TRIM(COALESCE(invoice.last_error, '')) = 'payment_init_in_progress'
            THEN TRUE
        ELSE FALSE
    END,
    CASE
        WHEN TRIM(COALESCE(invoice.last_error, '')) = 'payment_init_in_progress'
             AND NULLIF(TRIM(invoice.tbank_payment_id), '') IS NULL
            THEN TRUE
        ELSE FALSE
    END
FROM common_invoices invoice
WHERE NULLIF(TRIM(invoice.tbank_order_id), '') IS NOT NULL
   OR NULLIF(TRIM(invoice.tbank_payment_id), '') IS NOT NULL
   OR NULLIF(TRIM(invoice.payment_url), '') IS NOT NULL
   OR (
        TRIM(COALESCE(invoice.last_error, '')) = 'payment_init_in_progress'
        AND NULLIF(TRIM(invoice.tbank_payment_id), '') IS NULL
   );

-- Provider identities are compared across both legacy storage locations.
-- Separate inserts coerce invoice values into the registry column collation;
-- the duplicate-key no-op collapses only the same identity on the same invoice.
CREATE TEMPORARY TABLE cb_registry_order_identities ENGINE = InnoDB AS
SELECT ref.tbank_order_id AS provider_value, ref.invoice_id
FROM common_invoice_payment_refs ref
WHERE 1 = 0;

ALTER TABLE cb_registry_order_identities
    ADD PRIMARY KEY (provider_value, invoice_id);

INSERT INTO cb_registry_order_identities (provider_value, invoice_id)
SELECT NULLIF(TRIM(invoice.tbank_order_id), ''), invoice.invoice_id
FROM common_invoices invoice
WHERE NULLIF(TRIM(invoice.tbank_order_id), '') IS NOT NULL;

INSERT INTO cb_registry_order_identities (provider_value, invoice_id)
SELECT NULLIF(TRIM(ref.tbank_order_id), ''), ref.invoice_id
FROM common_invoice_payment_refs ref
WHERE NULLIF(TRIM(ref.tbank_order_id), '') IS NOT NULL
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_order_identities.invoice_id;

CREATE TEMPORARY TABLE cb_registry_payment_identities ENGINE = InnoDB AS
SELECT ref.tbank_payment_id AS provider_value, ref.invoice_id
FROM common_invoice_payment_refs ref
WHERE 1 = 0;

ALTER TABLE cb_registry_payment_identities
    ADD PRIMARY KEY (provider_value, invoice_id);

INSERT INTO cb_registry_payment_identities (provider_value, invoice_id)
SELECT NULLIF(TRIM(invoice.tbank_payment_id), ''), invoice.invoice_id
FROM common_invoices invoice
WHERE NULLIF(TRIM(invoice.tbank_payment_id), '') IS NOT NULL;

INSERT INTO cb_registry_payment_identities (provider_value, invoice_id)
SELECT NULLIF(TRIM(ref.tbank_payment_id), ''), ref.invoice_id
FROM common_invoice_payment_refs ref
WHERE NULLIF(TRIM(ref.tbank_payment_id), '') IS NOT NULL
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_payment_identities.invoice_id;

-- MySQL cannot reopen the same TEMPORARY table under two aliases in one
-- statement. Materialize the two identity kinds separately for the evidence
-- insert below.
CREATE TEMPORARY TABLE cb_registry_order_collisions ENGINE = InnoDB AS
SELECT identity_row.provider_value
FROM cb_registry_order_identities identity_row
WHERE 1 = 0;

ALTER TABLE cb_registry_order_collisions
    ADD PRIMARY KEY (provider_value);

INSERT INTO cb_registry_order_collisions (provider_value)
SELECT identity_row.provider_value
FROM cb_registry_order_identities identity_row
GROUP BY identity_row.provider_value
HAVING COUNT(*) > 1;

CREATE TEMPORARY TABLE cb_registry_payment_collisions ENGINE = InnoDB AS
SELECT identity_row.provider_value
FROM cb_registry_payment_identities identity_row
WHERE 1 = 0;

ALTER TABLE cb_registry_payment_collisions
    ADD PRIMARY KEY (provider_value);

INSERT INTO cb_registry_payment_collisions (provider_value)
SELECT identity_row.provider_value
FROM cb_registry_payment_identities identity_row
GROUP BY identity_row.provider_value
HAVING COUNT(*) > 1;

-- A ref is reusable only when it belongs to the same invoice, shares at least
-- one provider identity, has no contradictory identity/metadata and does not
-- contain an extra provider identity that is absent from the current invoice.
-- Missing values on the ref may safely be enriched from the invoice.
CREATE TEMPORARY TABLE cb_registry_compatible_refs (
    invoice_id BIGINT NOT NULL,
    payment_ref_id BIGINT NOT NULL,
    PRIMARY KEY (invoice_id, payment_ref_id)
) ENGINE = InnoDB;

INSERT INTO cb_registry_compatible_refs (invoice_id, payment_ref_id)
SELECT binding.invoice_id, ref.payment_ref_id
FROM cb_registry_current_bindings binding
JOIN common_invoice_payment_refs ref
  ON ref.invoice_id = binding.invoice_id
 AND (
        (
            binding.tbank_order_id IS NOT NULL
            AND NULLIF(TRIM(ref.tbank_order_id), '') = binding.tbank_order_id
        )
        OR (
            binding.tbank_payment_id IS NOT NULL
            AND NULLIF(TRIM(ref.tbank_payment_id), '') = binding.tbank_payment_id
        )
 )
WHERE NOT (
        binding.tbank_order_id IS NULL
        AND NULLIF(TRIM(ref.tbank_order_id), '') IS NOT NULL
    )
  AND NOT (
        binding.tbank_payment_id IS NULL
        AND NULLIF(TRIM(ref.tbank_payment_id), '') IS NOT NULL
    )
  AND NOT (
        binding.tbank_order_id IS NOT NULL
        AND NULLIF(TRIM(ref.tbank_order_id), '') IS NOT NULL
        AND NULLIF(TRIM(ref.tbank_order_id), '') <> binding.tbank_order_id
    )
  AND NOT (
        binding.tbank_payment_id IS NOT NULL
        AND NULLIF(TRIM(ref.tbank_payment_id), '') IS NOT NULL
        AND NULLIF(TRIM(ref.tbank_payment_id), '') <> binding.tbank_payment_id
    )
  AND NOT (
        binding.tbank_terminal_key IS NOT NULL
        AND NULLIF(TRIM(ref.tbank_terminal_key), '') IS NOT NULL
        AND NULLIF(TRIM(ref.tbank_terminal_key), '') <> binding.tbank_terminal_key
    )
  AND NOT (
        binding.amount_kopecks IS NOT NULL
        AND ref.amount_kopecks IS NOT NULL
        AND ref.amount_kopecks <> binding.amount_kopecks
    );

CREATE TEMPORARY TABLE cb_registry_quarantine ENGINE = InnoDB AS
SELECT invoice.invoice_id, ref.reason
FROM common_invoices invoice
JOIN common_invoice_payment_refs ref ON 1 = 0
WHERE 1 = 0;

ALTER TABLE cb_registry_quarantine
    ADD PRIMARY KEY (invoice_id);

-- Any OrderId/PaymentId associated with more than one invoice is unsafe on
-- every side of the collision. Keep all original provider columns as evidence.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT DISTINCT identity_row.invoice_id, 'provider_identity_cross_invoice_collision'
FROM cb_registry_order_identities identity_row
JOIN cb_registry_order_collisions collision
  ON collision.provider_value = identity_row.provider_value;

INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT DISTINCT identity_row.invoice_id, 'provider_identity_cross_invoice_collision'
FROM cb_registry_payment_identities identity_row
JOIN cb_registry_payment_collisions collision
  ON collision.provider_value = identity_row.provider_value
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- Preserve an earlier attempt's fail-closed decision when MySQL committed the
-- DML/DDL but Flyway could not record the version. Never promote such a row on
-- retry merely because the original in-progress marker was replaced.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT invoice.invoice_id,
       LEFT(COALESCE(
           NULLIF(SUBSTRING_INDEX(
               SUBSTRING(
                   invoice.last_error,
                   CHAR_LENGTH('migration_common_payment_registry:') + 1
               ),
               ';',
               1
           ), ''),
           'previous_migration_quarantine'
       ), 160)
FROM common_invoices invoice
WHERE invoice.last_error LIKE 'migration_common_payment_registry:%'
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- More than one compatible legacy ref is ambiguous even when every individual
-- row looks harmless.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT compatible.invoice_id, 'multiple_same_invoice_payment_refs'
FROM cb_registry_compatible_refs compatible
GROUP BY compatible.invoice_id
HAVING COUNT(*) > 1
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- A same-invoice ref which shares one identity but contradicts another one,
-- terminal or amount cannot be silently reused or bypassed with a new row.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT DISTINCT binding.invoice_id, 'same_invoice_payment_ref_mismatch'
FROM cb_registry_current_bindings binding
JOIN common_invoice_payment_refs ref
  ON ref.invoice_id = binding.invoice_id
 AND (
        (
            binding.tbank_order_id IS NOT NULL
            AND NULLIF(TRIM(ref.tbank_order_id), '') = binding.tbank_order_id
        )
        OR (
            binding.tbank_payment_id IS NOT NULL
            AND NULLIF(TRIM(ref.tbank_payment_id), '') = binding.tbank_payment_id
        )
 )
WHERE NOT EXISTS (
    SELECT 1
    FROM cb_registry_compatible_refs compatible
    WHERE compatible.invoice_id = binding.invoice_id
      AND compatible.payment_ref_id = ref.payment_ref_id
)
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- A legacy CURRENT row that does not represent the invoice's current provider
-- identity is a second live intent and therefore requires manual reconciliation.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT DISTINCT binding.invoice_id, 'current_payment_ref_mismatch'
FROM cb_registry_current_bindings binding
JOIN common_invoice_payment_refs ref
  ON ref.invoice_id = binding.invoice_id
 AND ref.status = 'CURRENT'
WHERE NOT EXISTS (
    SELECT 1
    FROM cb_registry_compatible_refs compatible
    WHERE compatible.invoice_id = binding.invoice_id
      AND compatible.payment_ref_id = ref.payment_ref_id
)
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- Multiple pre-existing CURRENT rows must be resolved before the unique guard
-- can be added. Quarantine all of them instead of choosing a winner by row id.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT ref.invoice_id, 'multiple_current_payment_refs'
FROM common_invoice_payment_refs ref
WHERE ref.status = 'CURRENT'
GROUP BY ref.invoice_id
HAVING COUNT(*) > 1
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- A matching identity does not authorize reviving a historical, terminal or
-- canceling lifecycle. Only an already-CURRENT non-terminal ref and an already-
-- APPLIED PAID ref are safe to reuse without guessing provider state.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT DISTINCT binding.invoice_id, 'matching_payment_ref_lifecycle_conflict'
FROM cb_registry_current_bindings binding
JOIN cb_registry_compatible_refs compatible
  ON compatible.invoice_id = binding.invoice_id
JOIN common_invoice_payment_refs ref
  ON ref.payment_ref_id = compatible.payment_ref_id
WHERE (
        (binding.invoice_status = 'PAID' AND ref.status <> 'APPLIED')
        OR (binding.invoice_status <> 'PAID' AND ref.status <> 'CURRENT')
    )
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- A different provider identity can still represent an unresolved operation.
-- Fail closed for every unknown/raw provider lifecycle. Only explicit terminal
-- history is harmless beside a new intent; PAID may also retain older APPLIED
-- refs, while non-PAID may retain its one exact-compatible CURRENT ref.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT DISTINCT binding.invoice_id, 'nonterminal_or_unknown_payment_ref_on_invoice'
FROM cb_registry_current_bindings binding
JOIN common_invoice_payment_refs ref
  ON ref.invoice_id = binding.invoice_id
LEFT JOIN cb_registry_compatible_refs compatible
  ON compatible.invoice_id = binding.invoice_id
 AND compatible.payment_ref_id = ref.payment_ref_id
WHERE ref.status NOT IN (
        'ARCHIVED',
        'CANCELED',
        'REJECTED',
        'REFUNDED',
        'PARTIAL_REFUNDED',
        'REVERSED',
        'PARTIAL_REVERSED'
    )
  AND NOT (
        binding.invoice_status = 'PAID'
        AND ref.status = 'APPLIED'
    )
  AND NOT (
        binding.invoice_status <> 'PAID'
        AND ref.status = 'CURRENT'
        AND compatible.payment_ref_id IS NOT NULL
    )
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- A provider Init carrying the in-progress marker was not durably finished,
-- even when a PaymentId happened to be stored. It cannot be retried or declared
-- absent safely and always becomes an explicit manual conflict.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT binding.invoice_id, CASE
        WHEN binding.init_without_payment_id = TRUE
            THEN 'payment_init_without_payment_id'
        ELSE 'payment_init_in_progress'
    END
FROM cb_registry_current_bindings binding
WHERE binding.init_in_progress = TRUE
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- Non-terminal invoices may expose a CURRENT payment only when the complete
-- provider identity is present and the URL passes a conservative SQL subset of
-- PaymentUrlPolicy.TBANK_PAYMENT. Exact Java validation remains fail-closed on
-- every runtime read. Missing/unsafe legacy projections are quarantined here.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT binding.invoice_id, 'unsafe_or_incomplete_current_payment'
FROM cb_registry_current_bindings binding
WHERE binding.invoice_status <> 'PAID'
  AND binding.init_in_progress = FALSE
  AND NOT (
        binding.invoice_status IN ('COLLECTING', 'READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID')
        AND binding.tbank_order_id IS NOT NULL
        AND binding.tbank_payment_id IS NOT NULL
        AND binding.tbank_terminal_key IS NOT NULL
        AND binding.amount_kopecks IS NOT NULL
        AND binding.amount_kopecks > 0
        AND binding.payment_url IS NOT NULL
        AND OCTET_LENGTH(binding.payment_url) = CHAR_LENGTH(binding.payment_url)
        AND OCTET_LENGTH(binding.payment_url) <= 1024
        AND binding.payment_url REGEXP '^[A-Za-z0-9:/?#@!$&''()*+,;=._~%-]+$'
        AND (
            LOWER(binding.payment_url) LIKE 'http://%'
            OR LOWER(binding.payment_url) LIKE 'https://%'
        )
        AND binding.payment_url REGEXP '^[[:alpha:]][[:alnum:]+.-]*://[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?(:[0-9]{1,5})?([/?#]|$)'
        AND binding.payment_url NOT REGEXP '[[:cntrl:]]'
        AND binding.payment_url NOT REGEXP '[[:space:]]'
        AND REGEXP_REPLACE(binding.payment_url, '%[0-9a-fA-F]{2}', '') NOT REGEXP '%'
        AND LOWER(binding.payment_url) NOT REGEXP '%(0[0-9a-f]|1[0-9a-f]|7f)'
        AND (
            SUBSTRING_INDEX(
                SUBSTRING_INDEX(
                    SUBSTRING_INDEX(
                        SUBSTRING_INDEX(binding.payment_url, '://', -1),
                        '/',
                        1
                    ),
                    '?',
                    1
                ),
                '#',
                1
            ) NOT REGEXP ':[0-9]+$'
            OR CAST(
                SUBSTRING_INDEX(
                    SUBSTRING_INDEX(
                        SUBSTRING_INDEX(
                            SUBSTRING_INDEX(
                                SUBSTRING_INDEX(binding.payment_url, '://', -1),
                                '/',
                                1
                            ),
                            '?',
                            1
                        ),
                        '#',
                        1
                    ),
                    ':',
                    -1
                ) AS UNSIGNED
            ) <= 65535
        )
    )
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- A terminal invoice with only an URL and no provider identity cannot produce
-- a meaningful APPLIED evidence row. Quarantine instead of clearing its sole
-- clue and inserting an all-NULL registry record.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT binding.invoice_id, 'paid_payment_without_provider_identity'
FROM cb_registry_current_bindings binding
WHERE binding.invoice_status = 'PAID'
  AND binding.tbank_order_id IS NULL
  AND binding.tbank_payment_id IS NULL
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- A registry-only CURRENT ref without a complete safe live projection is
-- equally unsafe. This includes PAID: only an already-APPLIED exact ref is a
-- safe terminal reuse, so a registry-only CURRENT must be reconciled manually.
INSERT INTO cb_registry_quarantine (invoice_id, reason)
SELECT DISTINCT ref.invoice_id, 'current_ref_without_safe_live_projection'
FROM common_invoice_payment_refs ref
JOIN common_invoices invoice
  ON invoice.invoice_id = ref.invoice_id
LEFT JOIN cb_registry_current_bindings binding
  ON binding.invoice_id = ref.invoice_id
WHERE ref.status = 'CURRENT'
  AND binding.invoice_id IS NULL
ON DUPLICATE KEY UPDATE invoice_id = cb_registry_quarantine.invoice_id;

-- Reuse every compatible ref as the evidence row for an interrupted Init.
UPDATE common_invoice_payment_refs ref
JOIN cb_registry_compatible_refs compatible
  ON compatible.payment_ref_id = ref.payment_ref_id
JOIN cb_registry_current_bindings binding
  ON binding.invoice_id = compatible.invoice_id
SET ref.tbank_order_id = COALESCE(NULLIF(TRIM(ref.tbank_order_id), ''), binding.tbank_order_id),
    ref.tbank_terminal_key = COALESCE(
        NULLIF(TRIM(ref.tbank_terminal_key), ''),
        binding.tbank_terminal_key
    ),
    ref.amount_kopecks = COALESCE(ref.amount_kopecks, binding.amount_kopecks),
    ref.status = 'INIT_CONFLICT',
    ref.reason = CASE
        WHEN NULLIF(TRIM(ref.reason), '') IS NULL
            THEN 'migration_payment_init_in_progress'
        ELSE ref.reason
    END,
    ref.updated_at = CURRENT_TIMESTAMP(6)
WHERE binding.init_in_progress = TRUE
  AND ref.status IN ('INIT_PREPARED', 'INIT_CONFLICT');

-- If no exact ref exists, retain the interrupted intent in a new conflict row.
-- A colliding OrderId stays on common_invoices as evidence and is repeated in
-- reason, while the ref identity remains NULL so the migration cannot violate
-- the pre-existing unique provider constraint.
INSERT INTO common_invoice_payment_refs (
    invoice_id,
    tbank_order_id,
    tbank_payment_id,
    tbank_terminal_key,
    amount_kopecks,
    status,
    reason,
    created_at,
    updated_at
)
SELECT
    binding.invoice_id,
    CASE
        WHEN occupied_order.payment_ref_id IS NULL
             AND occupied_payment.payment_ref_id IS NULL
             AND order_collision.provider_value IS NULL
             AND payment_collision.provider_value IS NULL
            THEN binding.tbank_order_id
        ELSE NULL
    END,
    CASE
        WHEN occupied_order.payment_ref_id IS NULL
             AND occupied_payment.payment_ref_id IS NULL
             AND order_collision.provider_value IS NULL
             AND payment_collision.provider_value IS NULL
            THEN binding.tbank_payment_id
        ELSE NULL
    END,
    binding.tbank_terminal_key,
    binding.amount_kopecks,
    'INIT_CONFLICT',
    CASE
        WHEN occupied_order.payment_ref_id IS NULL
             AND occupied_payment.payment_ref_id IS NULL
             AND order_collision.provider_value IS NULL
             AND payment_collision.provider_value IS NULL
            THEN 'migration_payment_init_in_progress'
        ELSE LEFT(CONCAT(
            'migration_init_conflict:',
            COALESCE(binding.tbank_order_id, '-'),
            '/',
            COALESCE(binding.tbank_payment_id, '-')
        ), 160)
    END,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM cb_registry_current_bindings binding
LEFT JOIN common_invoice_payment_refs occupied_order
  ON NULLIF(TRIM(occupied_order.tbank_order_id), '') = binding.tbank_order_id
LEFT JOIN common_invoice_payment_refs occupied_payment
  ON NULLIF(TRIM(occupied_payment.tbank_payment_id), '') = binding.tbank_payment_id
LEFT JOIN cb_registry_order_collisions order_collision
  ON order_collision.provider_value = binding.tbank_order_id
LEFT JOIN cb_registry_payment_collisions payment_collision
  ON payment_collision.provider_value = binding.tbank_payment_id
WHERE binding.init_in_progress = TRUE
  AND NOT EXISTS (
    SELECT 1
    FROM cb_registry_compatible_refs compatible
    WHERE compatible.invoice_id = binding.invoice_id
  )
  AND NOT EXISTS (
    SELECT 1
    FROM common_invoice_payment_refs existing_evidence
    WHERE existing_evidence.invoice_id = binding.invoice_id
      AND NULLIF(TRIM(existing_evidence.tbank_order_id), '') IS NULL
      AND NULLIF(TRIM(existing_evidence.tbank_payment_id), '') IS NULL
      AND existing_evidence.status = 'INIT_CONFLICT'
      AND existing_evidence.reason LIKE 'migration_init_conflict:%'
  );

-- No quarantined invoice may retain a CURRENT registry entry. Other lifecycle
-- rows remain untouched so webhook/cancel/refund evidence is preserved.
UPDATE common_invoice_payment_refs ref
JOIN cb_registry_quarantine quarantined
  ON quarantined.invoice_id = ref.invoice_id
SET ref.status = 'INIT_CONFLICT',
    ref.reason = LEFT(CONCAT(
        'migration_registry_conflict:',
        quarantined.reason,
        CASE
            WHEN NULLIF(TRIM(ref.reason), '') IS NULL THEN ''
            ELSE CONCAT('; previous=', ref.reason)
        END
    ), 160),
    ref.updated_at = CURRENT_TIMESTAMP(6)
WHERE ref.status = 'CURRENT';

UPDATE common_invoices invoice
JOIN cb_registry_quarantine quarantined
  ON quarantined.invoice_id = invoice.invoice_id
SET invoice.status = 'NEEDS_ATTENTION',
    invoice.payment_url = NULL,
    invoice.next_reminder_at = NULL,
    invoice.last_error = LEFT(CONCAT(
        'migration_common_payment_registry:',
        quarantined.reason,
        '; provider evidence preserved; manual reconciliation required'
    ), 512),
    invoice.updated_at = CURRENT_TIMESTAMP(6);

-- Reuse one safe same-invoice ref, enriching only fields that were absent on
-- that ref. PAID invoices have already applied their payment and are never
-- represented as an active CURRENT intent.
UPDATE common_invoice_payment_refs ref
JOIN (
    SELECT compatible.invoice_id, MIN(compatible.payment_ref_id) AS payment_ref_id
    FROM cb_registry_compatible_refs compatible
    GROUP BY compatible.invoice_id
    HAVING COUNT(*) = 1
) target
  ON target.payment_ref_id = ref.payment_ref_id
JOIN cb_registry_current_bindings binding
  ON binding.invoice_id = target.invoice_id
LEFT JOIN cb_registry_quarantine quarantined
  ON quarantined.invoice_id = binding.invoice_id
SET ref.tbank_order_id = COALESCE(NULLIF(TRIM(ref.tbank_order_id), ''), binding.tbank_order_id),
    ref.tbank_payment_id = COALESCE(
        NULLIF(TRIM(ref.tbank_payment_id), ''),
        binding.tbank_payment_id
    ),
    ref.tbank_terminal_key = COALESCE(
        NULLIF(TRIM(ref.tbank_terminal_key), ''),
        binding.tbank_terminal_key
    ),
    ref.amount_kopecks = COALESCE(ref.amount_kopecks, binding.amount_kopecks),
    ref.status = CASE WHEN binding.invoice_status = 'PAID' THEN 'APPLIED' ELSE 'CURRENT' END,
    ref.reason = CASE
        WHEN binding.invoice_status = 'PAID' THEN 'migration_paid_payment_registry'
        ELSE 'migration_current_payment_registry'
    END,
    ref.updated_at = CURRENT_TIMESTAMP(6)
WHERE quarantined.invoice_id IS NULL
  AND binding.init_in_progress = FALSE;

-- Provider identities which exist only on a safe invoice become a registry row.
-- No IGNORE clause is used: collision analysis above is the explicit safety
-- fence, so an unexpected invariant violation fails loudly.
INSERT INTO common_invoice_payment_refs (
    invoice_id,
    tbank_order_id,
    tbank_payment_id,
    tbank_terminal_key,
    amount_kopecks,
    status,
    reason,
    created_at,
    updated_at
)
SELECT
    binding.invoice_id,
    binding.tbank_order_id,
    binding.tbank_payment_id,
    binding.tbank_terminal_key,
    binding.amount_kopecks,
    CASE WHEN binding.invoice_status = 'PAID' THEN 'APPLIED' ELSE 'CURRENT' END,
    CASE
        WHEN binding.invoice_status = 'PAID' THEN 'migration_paid_payment_registry'
        ELSE 'migration_current_payment_registry'
    END,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM cb_registry_current_bindings binding
LEFT JOIN cb_registry_quarantine quarantined
  ON quarantined.invoice_id = binding.invoice_id
WHERE quarantined.invoice_id IS NULL
  AND binding.init_in_progress = FALSE
  AND NOT EXISTS (
    SELECT 1
    FROM cb_registry_compatible_refs compatible
    WHERE compatible.invoice_id = binding.invoice_id
  );

-- Runtime moves terminal PAID bindings to APPLIED and clears the live invoice
-- projection. Do the same for legacy rows: the ref keeps the complete provider
-- evidence and webhook lookup remains available through the registry.
UPDATE common_invoices invoice
JOIN cb_registry_current_bindings binding
  ON binding.invoice_id = invoice.invoice_id
LEFT JOIN cb_registry_quarantine quarantined
  ON quarantined.invoice_id = invoice.invoice_id
SET invoice.tbank_order_id = NULL,
    invoice.tbank_payment_id = NULL,
    invoice.tbank_terminal_key = NULL,
    invoice.tbank_payment_amount_kopecks = NULL,
    invoice.tbank_payment_created_at = NULL,
    invoice.payment_url = NULL,
    invoice.next_reminder_at = NULL,
    invoice.updated_at = CURRENT_TIMESTAMP(6)
WHERE quarantined.invoice_id IS NULL
  AND binding.init_in_progress = FALSE
  AND binding.invoice_status = 'PAID';

-- A virtual nullable key lets MySQL accept every non-CURRENT lifecycle row but
-- enforces at most one CURRENT registry row for each live invoice. Archive copy
-- and restore code deliberately excludes generated columns, so the guard stays
-- live-only and recomputes automatically after restore. Each DDL step is
-- guarded because MySQL commits DDL implicitly: if the schema change succeeds
-- but Flyway cannot record it, the next deployment can rerun this migration.
SET @cb_registry_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND COLUMN_NAME = 'current_invoice_id'
);
SET @cb_registry_sql = IF(
    @cb_registry_column_exists = 0,
    'ALTER TABLE common_invoice_payment_refs ADD COLUMN current_invoice_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = _utf8mb4''CURRENT'' THEN invoice_id ELSE NULL END) VIRTUAL',
    'SELECT ''current_invoice_id already exists'''
);
PREPARE cb_registry_stmt FROM @cb_registry_sql;
EXECUTE cb_registry_stmt;
DEALLOCATE PREPARE cb_registry_stmt;

-- Name-only idempotency guards are insufficient when an out-of-band object
-- already uses the expected name with a different definition. Validate the
-- exact pinned-MySQL shape and abort before the application starts without the
-- intended invariant. SIGNAL is not supported by the prepared-statement
-- protocol, so the invalid branch references an intentionally absent marker.
SET @cb_registry_generation_expression = (
    SELECT LOWER(REPLACE(REPLACE(REPLACE(
        GENERATION_EXPRESSION,
        CHAR(96),
        ''
    ), ' ', ''), CHAR(92), ''))
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND COLUMN_NAME = 'current_invoice_id'
    LIMIT 1
);
SET @cb_registry_generation_introducer = SUBSTRING(
    @cb_registry_generation_expression,
    LOCATE('=', @cb_registry_generation_expression) + 1,
    LOCATE(CHAR(39), @cb_registry_generation_expression)
        - LOCATE('=', @cb_registry_generation_expression) - 1
);
SET @cb_registry_generation_without_introducer = CONCAT(
    SUBSTRING_INDEX(@cb_registry_generation_expression, '=', 1),
    '=',
    SUBSTRING(
        @cb_registry_generation_expression,
        LOCATE(CHAR(39), @cb_registry_generation_expression)
    )
);
SET @cb_registry_column_valid = (
    SELECT COUNT(*) = 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND COLUMN_NAME = 'current_invoice_id'
      AND LOWER(DATA_TYPE) = 'bigint'
      AND LOWER(COLUMN_TYPE) = 'bigint'
      AND IS_NULLABLE = 'YES'
      AND UPPER(EXTRA) = 'VIRTUAL GENERATED'
      AND @cb_registry_generation_without_introducer
          = '(casewhen(status=''current'')theninvoice_idelsenullend)'
      AND (
          @cb_registry_generation_introducer = ''
          OR EXISTS (
              SELECT 1
              FROM INFORMATION_SCHEMA.CHARACTER_SETS charset_row
              WHERE CONCAT('_', LOWER(charset_row.CHARACTER_SET_NAME))
                    = @cb_registry_generation_introducer
          )
      )
);
SET @cb_registry_sql = IF(
    @cb_registry_column_valid = 1,
    'SELECT ''current_invoice_id guard valid''',
    'SELECT * FROM information_schema.__v200_invalid_current_invoice_id_guard'
);
PREPARE cb_registry_stmt FROM @cb_registry_sql;
EXECUTE cb_registry_stmt;
DEALLOCATE PREPARE cb_registry_stmt;

SET @cb_registry_unique_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND INDEX_NAME = 'uk_common_invoice_payment_refs_current_invoice'
);
SET @cb_registry_sql = IF(
    @cb_registry_unique_exists = 0,
    'ALTER TABLE common_invoice_payment_refs ADD UNIQUE KEY uk_common_invoice_payment_refs_current_invoice (current_invoice_id)',
    'SELECT ''uk_common_invoice_payment_refs_current_invoice already exists'''
);
PREPARE cb_registry_stmt FROM @cb_registry_sql;
EXECUTE cb_registry_stmt;
DEALLOCATE PREPARE cb_registry_stmt;

SET @cb_registry_unique_valid = (
    SELECT COUNT(*) = 1
       AND COALESCE(MIN(NON_UNIQUE), 1) = 0
       AND COALESCE(MAX(NON_UNIQUE), 1) = 0
       AND COALESCE(GROUP_CONCAT(
               CONCAT(SEQ_IN_INDEX, ':', COALESCE(COLUMN_NAME, '<expr>'))
               ORDER BY SEQ_IN_INDEX SEPARATOR ','
           ), '') = '1:current_invoice_id'
       AND COALESCE(SUM(SUB_PART IS NOT NULL), 1) = 0
       AND COALESCE(SUM(EXPRESSION IS NOT NULL), 1) = 0
       AND COALESCE(MIN(INDEX_TYPE), '') = 'BTREE'
       AND COALESCE(MAX(INDEX_TYPE), '') = 'BTREE'
       AND COALESCE(MIN(IS_VISIBLE), '') = 'YES'
       AND COALESCE(MAX(IS_VISIBLE), '') = 'YES'
       AND COALESCE(MIN(COLLATION), '') = 'A'
       AND COALESCE(MAX(COLLATION), '') = 'A'
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND INDEX_NAME = 'uk_common_invoice_payment_refs_current_invoice'
);
SET @cb_registry_sql = IF(
    @cb_registry_unique_valid = 1,
    'SELECT ''current invoice unique guard valid''',
    'SELECT * FROM information_schema.__v200_invalid_current_invoice_unique_guard'
);
PREPARE cb_registry_stmt FROM @cb_registry_sql;
EXECUTE cb_registry_stmt;
DEALLOCATE PREPARE cb_registry_stmt;

SET @cb_registry_due_index_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND INDEX_NAME = 'idx_common_invoice_payment_refs_invoice_status_updated'
);
SET @cb_registry_sql = IF(
    @cb_registry_due_index_exists = 0,
    'ALTER TABLE common_invoice_payment_refs ADD INDEX idx_common_invoice_payment_refs_invoice_status_updated (invoice_id, status, updated_at, payment_ref_id)',
    'SELECT ''idx_common_invoice_payment_refs_invoice_status_updated already exists'''
);
PREPARE cb_registry_stmt FROM @cb_registry_sql;
EXECUTE cb_registry_stmt;
DEALLOCATE PREPARE cb_registry_stmt;

SET @cb_registry_lookup_valid = (
    SELECT COUNT(*) = 4
       AND COALESCE(MIN(NON_UNIQUE), 0) = 1
       AND COALESCE(MAX(NON_UNIQUE), 0) = 1
       AND COALESCE(GROUP_CONCAT(
               CONCAT(SEQ_IN_INDEX, ':', COALESCE(COLUMN_NAME, '<expr>'))
               ORDER BY SEQ_IN_INDEX SEPARATOR ','
           ), '') = '1:invoice_id,2:status,3:updated_at,4:payment_ref_id'
       AND COALESCE(SUM(SUB_PART IS NOT NULL), 1) = 0
       AND COALESCE(SUM(EXPRESSION IS NOT NULL), 1) = 0
       AND COALESCE(MIN(INDEX_TYPE), '') = 'BTREE'
       AND COALESCE(MAX(INDEX_TYPE), '') = 'BTREE'
       AND COALESCE(MIN(IS_VISIBLE), '') = 'YES'
       AND COALESCE(MAX(IS_VISIBLE), '') = 'YES'
       AND COALESCE(MIN(COLLATION), '') = 'A'
       AND COALESCE(MAX(COLLATION), '') = 'A'
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'common_invoice_payment_refs'
      AND INDEX_NAME = 'idx_common_invoice_payment_refs_invoice_status_updated'
);
SET @cb_registry_sql = IF(
    @cb_registry_lookup_valid = 1,
    'SELECT ''current invoice lookup guard valid''',
    'SELECT * FROM information_schema.__v200_invalid_current_invoice_lookup_guard'
);
PREPARE cb_registry_stmt FROM @cb_registry_sql;
EXECUTE cb_registry_stmt;
DEALLOCATE PREPARE cb_registry_stmt;

DROP TEMPORARY TABLE cb_registry_quarantine;
DROP TEMPORARY TABLE cb_registry_compatible_refs;
DROP TEMPORARY TABLE cb_registry_payment_collisions;
DROP TEMPORARY TABLE cb_registry_order_collisions;
DROP TEMPORARY TABLE cb_registry_payment_identities;
DROP TEMPORARY TABLE cb_registry_order_identities;
DROP TEMPORARY TABLE cb_registry_current_bindings;
