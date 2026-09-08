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
