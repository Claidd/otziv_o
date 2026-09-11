# Built application browser smoke

These Playwright tests run the actual production Angular bundles in Chromium, through two loopback-only static servers. They do not replace components or templates with a test application.

```sh
cd frontend
npm ci --no-audit --no-fund
npm run build -- --configuration production
cd ../mobile
npm ci --no-audit --no-fund
npm run build:prod
cd ../infrastructure/browser-smoke
npm ci --no-fund
npx playwright install chromium
npm test
```

CI installs Chromium's Linux dependencies with `npx playwright install --with-deps chromium`. Node 24.18.0 and Playwright 1.61.1 are pinned. Chromium's sandbox stays enabled. Ports 43171 and 43172 must be free; an existing server is never reused. The server refuses absent builds and has no API proxy fallback.

On the disposable Ubuntu GitHub runner, `hosted-sandbox.mjs prepare` installs a root-owned AppArmor profile for the exact installed headless-shell executable. It grants that executable `userns` following [Canonical's per-application policy](https://ubuntu.com/blog/ubuntu-23-10-restricted-unprivileged-user-namespaces). Host-wide namespace restrictions stay enabled. A real browser preflight verifies the applied profile, renderer user-namespace mapping, `NoNewPrivs=1`, seccomp filtering and absence of sandbox-disabling flags. The workflow must trap exit and call `hosted-sandbox.mjs cleanup`; cleanup refuses an altered or foreign profile. The preflight JSON lives in `$RUNNER_TEMP/otziv-hosted-sandbox-proof.json` because Playwright clears its test-results directory before running. This helper is CI-only; it does not change deployment hosts.

## Coverage

- Desktop and mobile web: unknown payment status cannot grant payment capability; all three consents are required; a pending request cannot submit twice; HTTP 503 with an unknown outcome and a transport timeout do not replay a write or claim success; malformed capabilities on a return refresh clear previously actionable data; receipt edits survive status reads; only a confirmed status read renders success.
- Desktop and mobile web: a late payment response after navigation through the real consent link cannot open a bank from the previous page, including after browser Back returns to the payment form. This scenario caught an Ionic cached-page lifecycle regression that component-only tests missed.
- Desktop and mobile web: protected routes lead an anonymous visitor to a local OIDC sign-in fixture with PKCE.
- Desktop: a synthetic WORKER session cannot enter the administrator dictionary. A pending phone save cannot overwrite a newer A→B or A→B→A editor session, and switching tabs cancels a hidden list request.
- Desktop: the bundled Keycloak SDK preserves a still-valid session after an HTTP 503 or a lost connection during token refresh, while `invalid_grant` clears the session and returns to sign-in. These cases use synthetic issuer responses and exercise the real SDK callback and navigation behavior.
- Desktop payment journal: changing filters aborts pending pagination and status reads; search aborts the bootstrap journal before its debounce completes. Only the current rows, page, loading state and errors survive. A delayed manual-task A save success or failure preserves the newer B editor and its unsaved comment without replaying the write.
- Mobile web payment journal: the real app login/PKCE/callback flow opens the Ionic journal with a synthetic OIDC response. Sorting cancels a pending page and sends `sortDirection` with page zero; the UI preserves the server page order. A newer status wins while the previous status is loading, including stale-error suppression and current counts/loading.

All API and OIDC responses contain local synthetic data. Browser routing blocks every non-fixture HTTP origin and every WebSocket; service workers are disabled. Unknown mutation endpoints fail the test. The known activity telemetry endpoint has an explicit synthetic response. No VPS database, real bearer token, provider, payment, message, or account is contacted. Downloads during dependency/browser installation are separate from test browsing.

HTML/JUnit reports, browser/network evidence and a SHA-256 manifest of the built entry points/chunks are retained in `playwright-report/` and `test-results/`. Failures additionally retain screenshots and traces. Do not publish traces generated against real accounts; this harness requires fixtures only.

The OIDC fixture exercises client routing and SDK refresh behavior, not Keycloak token validation or backend authorization. The separate local prod-like smoke checks login against a real local Keycloak instance. Unknown-result tests verify client behavior; they do not establish provider idempotency or exactly-once payment delivery. Mobile-web uses a touch viewport in Chromium, not a native device. The separate Android CI job runs native lint and debug assembly; it does not establish emulator, push, deep-link, camera, or iOS behavior.
