# Order-details write settlement, 2026-09-07

The cached order page now re-reads the current order after a write from an earlier visit settles. Previously, a return GET could finish before the old write committed; the old callback was correctly rejected, but no subsequent read repaired the stale display. This affected successful and ambiguous failed responses.

`PageWriteTracker` remains page-local. Its optional resource key limits reconciliation to the same order route; its `begin()`/settlement pair covers the complete sequential review/order/company note command. Leaving or destroying a page does not cancel or replay dispatched writes. Manager callers retain their existing unkeyed behavior.

Order-details tracks review/detail/recovery commands, review text/form/delete/photo commands, notes, payment route/link/paper-invoice commands and report jobs. A reconciliation GET updates server state while preserving current drafts, modal ownership, selected review, mutation status and feedback. Report reconciliation reads only a currently open report and cannot implicitly start another report job. A hidden cached page ignores route-read events until enter.

Validation uses the real Angular component and feature HTTP services with `HttpTestingController`; only native UI dependencies are test providers. The 27 new cases cover nine command paths with late success/error, sequential notes including partial failure, A→B and A→B→A, hidden/destroyed pages, preservation of newer drafts, failed reconciliation and report reads without another POST.

- Current targeted runtime suite: **50/50 passed** across eight files.
- Architecture ownership guards: **4/4 passed**.
- Mobile production build: **passed**, 2026-09-07 13:41 UTC.
- Counterfactual: the same 27 new cases against the saved pre-fix page/facades produced **24 failures and 3 passes**. Failures detect the missing settlement reads and hidden-page route read. No working-tree source was reverted for this check.

Commands, run from `mobile`:

```text
npm run test:runtime -- --include='src/app/features/order-details*.runtime.spec.ts' --include='src/app/features/order-details/*.facade.spec.ts' --include='src/app/features/manager-board-http.runtime.spec.ts'
npm run build:prod
```

From the repository root:

```text
node --test mobile/test/client-architecture.test.mjs
```

Evidence is under `.codex-tmp/remediation-completion-20260907/`: `order-details-reconciliation-targeted-v3.log`, `order-details-reconciliation-architecture.log`, `order-details-reconciliation-build.log`, `order-details-reconciliation-before-v2.log`, and `order-details-before/before-source-manifest.json`. Earlier setup/compile attempts remain separately recorded. These checks certify the local source/build, not a production-signed native rollout.
