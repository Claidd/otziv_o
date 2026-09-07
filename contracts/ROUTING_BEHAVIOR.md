# Routing helper compatibility

The comparison below describes the existing web/mobile behavior. The common SDK does not choose a bank, recipient or permission on behalf of the server. `fixtures/payment-routing-parity.json` and `client-common.test.mjs` exercise both actual platform adapters.

| Responsibility | Web | Mobile | Decision |
|---|---|---|---|
| Accounting recipient/source labels; canceled task visibility | Shared package re-export | Shared package re-export | One implementation; identical pre-existing behavior |
| Route error code from body, nested error/properties, trim/case normalization | `manualPaymentRouteErrorCode` | `mobilePaymentRouteErrorCode` | Same result; unknown codes are not retryable |
| Stale route/required recipient | Retryable classification for explicit user reconciliation | Same | Classification never automatically repeats a write |
| Unresolved accounting target | Not retryable, explanation to bind recipient | Same | No financial fallback |
| Recipient key | Explicit key first; task ID **and generation** required; profile otherwise | Same | Existing names/types stay in platform adapters |
| Recipient label/effect | Historical key, task bank recipient and accounting target, server effect text | Same | Runtime parity table covers task/owner/legacy/unresolved values |
| Refresh recipient options | Rebuild safe default row; keep reason/receipt; clear both acknowledgements | Same | Missing default with remaining amount throws; no resubmission |
| Missing/empty bank route | `isBankPaymentRouteType` returns **false** | `isBankPaymentRoute` returns **true** | Preserve existing mobile legacy empty-route presentation; do not silently merge these helpers |
| Explicit `BANK_LINK`, `TBANK_LINK`, `TOCHKA_LINK` | Bank route | Bank route | Same; trim/case accepted |
| Unknown nonempty route | Not a bank route | Not a bank route | Common public-invoice guard also disables payment/report capabilities |
| Bank recipient distinct from accounting recipient | Separate `manualPaymentBankRecipientName` display helper | No corresponding helper/API in this adapter | Preserve web-specific presentation; do not invent mobile UI |
| Task target selector and overrun acknowledgement | `manualPaymentTaskTargetKey`, `manualPaymentTaskOverrunAcknowledgementRequired` | No corresponding helper in this adapter | Keep web-only settings responsibility; its existing tests remain authoritative |

Future consolidation of the routing adapters must preserve this matrix. An empty legacy route is not evidence that a new unknown route should be accepted. Unknown public invoice status/route disables `payable` and `clientReportable`; unknown single-payment status/method/page mode disables all existing payment flags. Raw strings remain visible for diagnosis.
