# Financial and manager scenario measurements

Run from `backend` with Java 26 and Docker available:

```text
./mvnw -B -ntp -Dtest=FinancialScenarioBenchmark -Dotziv.benchmark.source=<source-label> test
```

On Windows use `mvnw.cmd`. Maven creates an isolated pinned MySQL container,
runs the real application and Flyway, then creates synthetic data: 1,000 invoices,
10,000 payment links, 20 managers and 50 current manager-control items.
No production credentials, database or external provider are used.
Scheduled jobs are stopped before fixture creation and measurements.

The three calls exercise `CommonBillingService.managerBoardPage`,
`PaymentLinkService.adminLinks` and `ManagerControlService.managerDetails` through
the actual Spring/Hibernate implementation. Each scenario has eight warmup calls
and 24 measurements per worker at concurrency 1, 4 and 8, with an eight-connection
JDBC pool. The report records p50/p95, throughput, actual JDBC execute counts,
SQL time, commits, rollbacks and MySQL `EXPLAIN FORMAT=JSON` for bound SELECTs.
Prepared values are synthetic and are not printed in the report.

Output: `backend/target/performance/scenario-benchmark.json`. Preserve each report
and a manifest of its `src/main` files before building another version. Run the
same harness against the retained sources before each decomposition step and
against the completed candidate. Use sequential runs on an otherwise quiet host.
The finance and manager pre-refactoring sources may be different snapshots;
the comparison tool selects the appropriate baseline per scenario explicitly.

```text
node infrastructure/scripts/performance/compare-scenarios.mjs before-finance.json before-manager.json after.json evidence-directory
node --test infrastructure/scripts/performance/compare-scenarios.test.mjs
```

Comparison refuses mixed harness fingerprints, fixtures, database images, pool
sizes, incomplete matrices, duplicate rows, pilot sample sizes and rollbacks.
Read and retain the query plans alongside the aggregate numbers.

CI runs the same target with `-Dotziv.benchmark.iterations=4` and
`-Dotziv.benchmark.query-budgets=true`. This is a query-regression check with a
shorter timing sample. The initial reviewed ceilings are 128, 46 and 58 SQL
executions per request, respectively; the pre-gate verified implementation used
126, 44 and 56. These ceilings freeze existing work, including remaining N+1
costs, and are not an optimization target. Budget changes require a scenario,
query-plan and transaction review; CI must not regenerate them automatically.

Timing has no universal pass/fail threshold. Local p95 and observed saturation
do not establish a production SLO or a supported user count. The product and
operations owners must agree those limits against a production workload before
a capacity or SLO acceptance claim. Mutation correctness, rollback and concurrent
money changes remain the responsibility of the separate real-MySQL suites.

## Interactive pages

`InteractiveBoardBenchmark` runs the real manager and specialist readers with 1,000
companies/orders and 20 workers in disposable MySQL. It also checks cabinet/team/today,
prepared-progress timestamps, rollback, date separation and committed team reassignment.
Run it explicitly with `-Dtest=InteractiveBoardBenchmark`; reports are written to
`backend/target/performance/interactive-*.json`. Its short in-process timing samples
are diagnostic and do not establish an HTTP SLO.

`interactive-http.js` uses k6 constant-arrival-rate scenarios, independently per section.
It validates a successful JSON page/summary, reports P95/P99 only for correct responses,
and fails on invalid responses or dropped iterations. Sections include companies,
orders, all six specialist tabs, profile/team, their explicit refresh paths and today.
Run against a prepared environment with a normally authenticated user's token in
`AUTH_TOKEN`. Credentials/tokens must not be put in command arguments or committed files.

For the existing isolated local stack, `local-oidc.py` performs ordinary PKCE sign-in
using the protected external prod-local env. It is restricted to loopback HTTP and
never creates users, grants roles or changes Keycloak configuration. Install `requests`
in an isolated Python environment, then run, for example:

```text
python infrastructure/scripts/performance/local-oidc.py --env-file <external-prod-local.env> --k6-image <resolved-image@sha256:digest> --rate 4 --seconds 250 --source-label <revision> --role-label admin --output <private-results-directory>
```

The default three routes produce approximately 1,000 responses each at 12 requests/s.
The helper refuses a run longer than the token lifetime. Repeat independent runs with
fresh normal sign-ins for larger samples. Reports contain image IDs, workload, dates
and a role label, with no username or token. Container CPU/memory limits are recorded
explicitly: the default local app is uncapped and must not be described as a VPS-sized
app. For a paired capped comparison apply identical app limits to both versions, while
retaining the isolated stack's networks, volumes and external-send restrictions.
The k6 duration covers request transmission,
waiting and the complete response through nginx; DNS/connect/TLS establishment is
separate. Client thresholds default to P95 <100 ms/P99 <200 ms, configurable for a
remote network boundary. Server SLO and client RTT must be reported separately.

The helper also snapshots the allowlisted `otziv_http_*` counters immediately before
and after each run. `server-summary.json` contains only this interval's successful
requests, mean SQL executions/time, interpolated histogram P95/P99 and the exact
fraction at or below the 100 ms bucket. Counter resets invalidate the comparison.
Older app versions without these metrics produce no server results; do not invent
them from controller timings. The server boundary includes security and serialization,
but excludes proxy/network/connector waiting. Pair server counts with k6 validation.

Warm the application consistently and avoid overlapping image builds or large test
suites with comparisons. Preserve baseline reports before changing an image. Test
startup/cache misses/explicit refresh separately, pair latency with nonempty fixtures
and access assertions, and report empty pages or expected 403 separately. Do not
claim tail stability from a handful of calls.

`capture-vps.py` collects read-only Prometheus, image/source metadata, resource pressure
and MySQL digest counters through verified SSH. Use the existing key/known_hosts and
an external or ignored output path. `--window 24h` is a lookback, not proof of 24 hours
of continuous data. After deployment use a window wholly inside the new instance's
uptime. Compare digest counter deltas, never accumulated totals from different windows.
Empty series mean unavailable. Historical endpoint timers and new full servlet timers
measure different boundaries and must not be placed in a misleading before/after ratio.
