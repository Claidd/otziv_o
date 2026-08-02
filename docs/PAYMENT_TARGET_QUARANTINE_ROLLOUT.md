# Payment target quarantine rollout

## Compatibility contract

- A truly absent legacy manual external URL continues to use
  `ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL`.
- A persisted nonblank/control-bearing URL that fails the allow-list is never
  substituted with that default. Read APIs return an empty target and mark the
  profile unconfigured; routing falls back to T-Bank and manual links are not
  payable until repaired.
- `manualPaymentUrlReplacementConfirmed` is additive and optional on profile
  policy and manual-task update requests. Missing/false means a mixed-version
  client cannot overwrite a quarantined raw value with its displayed fallback.
  `true` requires an explicit, nonblank, allow-listed replacement.
- Existing Java callers keep the previous record constructors, and older JSON
  clients may omit the marker.

## Provider URL quarantine

- A T-Bank payment ID plus a missing/unsafe ordinary payment URL is moved to
  `NEEDS_RECONCILIATION`; no second `Init` is issued.
- Unsafe cached or fresh SBP payloads and unsafe optional T-Bank fallback URLs
  are cleared and moved to `NEEDS_RECONCILIATION` when a bank payment exists.
- CommonBilling commits `NEEDS_ATTENTION`, clears the unsafe cached URL, retains
  bank identifiers for webhook/manual reconciliation, and returns HTTP 502 only
  after the quarantine transaction has committed.

## Reconciliation rotation

`V1_10_189__payment_link_reconciliation_attempts.sql` adds only
`payment_links.bank_reconciliation_attempted_at` and the online due index. The
scheduler records the attempt under the existing row lock even when the bank
status is unchanged or the provider call fails. Page zero therefore rotates
through old and new rows instead of repeatedly selecting the same oldest 50.
The five-minute freshness check under the lock also prevents two scheduler
instances from immediately processing the same payment.

The migration follows V188 and is independent of the external-review V190
migration. Archive copy/restore remains compatible because the new live-table
column is nullable and is deliberately not part of archived business data.

## Safe deployment and rollback

1. Deploy the migration and backend first. Old web/mobile clients remain valid
   and cannot release quarantined recipients.
2. Deploy web and mobile clients that send the explicit replacement marker.
3. Monitor counts of `NEEDS_RECONCILIATION`, CommonBilling `NEEDS_ATTENTION`,
   and `unsafe_*_url` / `unsafe_*_payload` error codes. Resolve bank state before
   allowing a new payment.
4. Do not roll back by deleting the nullable column. An application rollback
   safely ignores it; keep the additive schema until the old version is retired.

Regression coverage lives in `PaymentUrlPolicyTest`,
`PaymentProfileServiceTest`, `ManualPaymentTaskServiceTest`,
`PaymentLinkServiceTest`, `CommonBillingServiceTest`, and the migration contract
test.

## CommonBilling current-payment registry rollout

`V1_10_200__common_billing_current_payment_registry.sql` backfills the live
CommonBilling payment projection into `common_invoice_payment_refs` and adds a
nullable generated unique key that permits at most one `CURRENT` ref per
invoice. Ambiguous provider identities are retained as evidence, but the
affected invoice is moved to `NEEDS_ATTENTION`, its public payment URL and next
reminder are cleared, and no winner is guessed.

This migration is additive at the schema level, but it is **not safe for an
ordinary rolling deployment with an old writer still running**. The old backend
can create an invoice-only T-Bank binding after the backfill snapshot and does
not maintain the new registry row. Use this sequence:

1. Disable/drain every old backend instance and wait for in-flight payment Init
   and webhook requests to finish.
2. Keep the public payment ingress unavailable while no compatible backend is
   running; do not route it to an old instance.
3. Start one new backend instance, let Flyway complete V200, and verify there is
   no startup/migration error.
4. Confirm that every non-quarantined invoice exposing a payment URL has exactly
   one `CURRENT` registry row, and that no invoice has more than one `CURRENT`
   row.
5. Re-enable ingress and then scale out only the new backend version.

Do not roll the application back to a pre-registry writer while payment ingress
is enabled. A rollback requires the same drain window and a compatible forward
fix; deleting the generated column or unique key is not a safe rollback.
