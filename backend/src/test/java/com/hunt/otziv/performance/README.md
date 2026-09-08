# Financial scenario benchmark and CTE plan companion

The comparative harness is `FinancialScenarioBenchmark.java`; its SHA256 is `50f9009d186b3ed062531d14bf3a348b099549fb4f91420dc3c6f1aae00e3984`. Do not change it between before/after measurements. Select the two financial before scenarios from `performance-before-finance.json` and the manager scenario from `performance-before-manager.json`; each file contains all three scenarios, but their source slices have different purposes.

Manual comparative command (Java26 and Docker; disposable synthetic MySQL only):

```text
./mvnw.cmd -B -ntp -Dtest=FinancialScenarioBenchmark -Dotziv.benchmark.source=<precise-source-label> test
```

The parent captures SQL counts for all prepared statement executions. Its EXPLAIN export accepts SELECT only, so the board WITH queries need the independent companion below. The parent harness remains unchanged.

```text
./mvnw.cmd -B -ntp -Dtest=FinancialCtePlanEvidence -Dotziv.cte.source=<precise-source-label> test
```

`FinancialCtePlanEvidence` overrides the single test, temporarily sets `otziv.benchmark.iterations` from `otziv.benchmark.cte.iterations` (default1) and uses an explicit `otziv.benchmark.source`, otherwise a `plan-only` source label, and invokes the real parent service/fixture path. It then replays the exact captured parameter setters into `EXPLAIN FORMAT=JSON` for WITH statements. It requires both invoice-ID selection and invoice-count CTE plans. Properties are restored afterward. It saves `target/performance/cte-plans.json` with the SQL, parameter setters, structured plans, and parent/companion hashes.

The default one-iteration `target/performance/scenario-benchmark.json` from the companion is **plan-only pilot output**, never comparative before/after evidence. A deliberate full after run uses the unchanged parent measurement with24 iterations and an explicit source label:

```text
./mvnw.cmd -B -ntp -Dtest=FinancialCtePlanEvidence -Dotziv.benchmark.cte.iterations=24 -Dotziv.benchmark.source=after-completion -Dotziv.benchmark.query-budgets=true test
```

This produces the same9 scenario/concurrency groups with24/96/192 samples as the comparative parent, followed by CTE EXPLAIN outside the timed calls. The wrapper only sets/restores system properties and exports extra plans; parent measurement code and SHA remain identical. Run the companion in independent source snapshots and preserve the existing full-run reports. The name `FinancialCtePlanEvidence` does not match default Surefire test discovery; run it explicitly with `-Dtest`.

Plans are optimizer estimates, not measured rows or server CPU (`EXPLAIN ANALYZE` is not used). They cover the captured seeded parameter set, not every filter/cardinality. Concurrency1/4/8, pool8, homogeneous fixtures and a shared host do not establish causal production latency improvements. Query counts and plans can substantiate the selected bounded scenarios; they do not prove the absence of N+1 in unmeasured paths or guaranteed external delivery.
