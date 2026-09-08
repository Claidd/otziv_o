# WhatsApp remote-session recovery

Automatic restart now stops admission and invalidates the session event generation immediately. It waits for initialization, accepted webhook work and HTTP task settlement, then closes the old client once. Cleanup rejection or deadline failure terminates the gateway; it never authorizes another client in that process. Shutdown shares the same transition. The task drain defaults to 30 seconds (maximum 300), cleanup to 5 seconds, and replacement startup to the configured startup-ready bound (maximum 900 seconds). The Compose stop grace remains larger than drain plus cleanup.

Local Chromium sessions keep their existing persistent auth and deployment model. Remote profiles additionally require `flock` and a persistent `/auth/remote-sessions` directory. The gateway holds a Linux open-file-description lock for the profile for its entire process lifetime. A second process using the same volume/profile cannot start or reconcile it. A dirty record is fsynced before attach and includes the browser identity, generation and, after allocation, actual CDP target identity. It becomes clean only after successful adapter cleanup and a fresh remote observation of the same browser with no page or WhatsApp worker targets. The gateway disconnects from the shared browser; it never closes that browser.

Process death releases the OS lock but **does not clear the dirty record**. A missing record is also unverified history, not proof of a clean existing profile. Therefore an existing remote deployment needs an explicit cutover; do not erase `/auth` or add an automatic clean-on-start flag. The outbound operation ledger is independent and must be preserved: session reconciliation does not permit replaying UNKNOWN sends.

## Initial cutover or recovery after ambiguous cleanup

1. Stop new producers and drain the gateway under the existing rollout procedure. Stop that gateway container. Do not start a second gateway attached to the same profile. Retain `/auth`, the outbound ledger and remote-session files.
2. Through the owning browser-profile management interface, identify the intended profile and inspect its pages. Close the old gateway-owned WhatsApp page after resolving any live provider operation. If the profile is intentionally shared with other pages, arrange an explicit maintenance window; the reconciliation command requires no page/WhatsApp-worker targets and does not close anything itself.
3. Run the inspection using the exact gateway service's mounted volume, environment and private network. For example, after stopping `whatsapp_lika`:

   ```sh
   docker compose --env-file /secure/env.prod run --rm --no-deps --entrypoint node whatsapp_lika remote-session-admin.js inspect
   ```

   The result contains safe browser/target IDs, the current generation (or `absent`) and CLEAN/DIRTY/UNINITIALIZED state. It does not print cookies, tokens, URLs or message content. A writer-lock failure means another gateway/admin process still owns this volume/profile; do not bypass it.
4. Verify that the reported browser belongs to the intended profile and that `activeTargetIds` is empty. Reconcile the exact observed generation and browser, supplying an operator identifier and an external maintenance/evidence reference:

   ```sh
   docker compose --env-file /secure/env.prod run --rm --no-deps --entrypoint node whatsapp_lika remote-session-admin.js reconcile OBSERVED_GENERATION OBSERVED_BROWSER_ID OPERATOR_ID EVIDENCE_REFERENCE
   ```

   Inspection is repeated under the same writer lock. A changed generation/browser or any remaining page/WhatsApp target rejects the command. A successful command appends and fsyncs its audit before writing the new clean generation. No TTL, force-clear, browser deletion or UNKNOWN-send replay is performed.
5. Restart only that gateway and verify authenticated readiness and task metrics before resuming producers. A new failure leaves the barrier intact. Repeat separately for each profile.

The operator audit proves the explicit local action and observed remote state. It does not prove historical message delivery or independence of another host that uses a different volume. All gateways for a profile must use the same persistent volume and established single-owner deployment policy.

## Verification

`node --test whatsapp/*.test.js` exercises normal/rejected/hung cleanup, concurrent restart/shutdown, event generations and a real child-process send crash with ledger UNKNOWN recovery. `remote-session-smoke.js`, included in the existing container compatibility smoke, launches only an owned offline Chromium fixture: actual Linux flock rejects another writer; a real child process creates a remote page and dies; the next process is denied attach; live targets reject reconciliation; observed closure and audited reconciliation permit recovery. Failed page cleanup preserves DIRTY and never closes the profile-owning browser. No live provider account or message is used.
