# Build-support reactor

This finite reactor builds the reviewed downstream audit adapter and
Testcontainers transport. Each module has its own POM. An identical
explicit `pluginManagement` policy in each POM pins the effective build tools;
`plugin-policy.json` and `audit.py` reject missing, changed or unreviewed active
plugins. The policy is an inventory and compatibility guard, not a vulnerability
exception or a claim that every upstream artifact is currently clean.

Build with JDK 25 and Maven 3.9.15 before building the backend:

```sh
mvn -B -ntp -f backend/build-support/pom.xml install
python -m unittest discover -s backend/build-support -p test_audit.py
```

The application, reactor and standalone issuer audits must all succeed in the same
authenticated CI job. Run each even if an earlier audit reports vulnerabilities, and
combine their exit statuses. Each of the three reactor projects uses one direct goal rather than the
backend `security-audit` lifecycle, avoiding a duplicate application audit:

```sh
python backend/build-support/audit.py \
  --maven "$GITHUB_WORKSPACE/backend/mvnw" \
  --settings "$HOME/.m2/settings.xml" \
  --suppression-file "$GITHUB_WORKSPACE/infrastructure/runtime-security/maven-false-positives.xml" \
  --data-directory "$RUNNER_TEMP/otziv-dependency-check-data"
```

The release audit uses free public NVD and OSV data. No Sonatype account or
credits are required; its analyzer is explicitly disabled. NVD still blocks
CVSS 7+ and analyzer errors. After all three Maven audit stages,
`python backend/build-support/osv_audit.py` queries every Maven package from all
five reports, including test, provided and effective plugin dependencies.
OSV HIGH/CRITICAL, unknown severity, missing reports, incomplete pagination and
API failures are blocking. The OSV report preserves package coverage, report
hashes, raw API responses and advisory details. Only package coordinates are
sent to OSV. See https://google.github.io/osv.dev/api/.

Only the public NVD database is cached. Maven settings and all reports remain
outside that cache; no paid credentials enter the workflow.

Before the audit the runner obtains the actual complete Maven effective reactor
and validates every active plugin against the policy. It enables application,
test, provided, runtime, system and plugin dependency scanning explicitly. The CLI properties were checked against the built adapter's inherited Mojo
descriptor. This includes `odc.plugins.scan`; the superficially similar
`scanPlugins` CLI property does not bind the upstream parameter.

The runner audits all three installed projects sequentially with `-N`, after one
complete effective-model preflight. It continues through every project even if
one reports vulnerabilities, retaining the first failure exit code. This avoids
Maven skipping dependent projects after a failed reactor module. The shared cache
remains active for each invocation. `target/audit-report-coverage.json` records each
project's actual exit code, report presence and digest. Each project's fresh
`target/plugin-audit-scope.json` must match its GAV and current POM hash; OSV also
checks this receipt for the application and standalone issuer. Missing or malformed reports cannot turn a
zero process exit into complete coverage. A failed/partial reactor audit remains
failed; the runner never repeats an already audited project to hide its failure.

Upload each project's `target/dependency-check-report.json` and `.html`, plus the
aggregate `target/audit-policy-verification.json` and `audit-report-coverage.json`.
Preserve raw reports before changing dependency graphs or audit policy. The local
fixture tests exercise credential-free execution, command bindings, exact reactor coverage,
effective-version/override drift, unknown plugins, cache isolation, nonsecret plan
mode, failed audit propagation and rejection of stale/incomplete report sets.

The unused Site fork has been removed. Maven 3.9.15 still injects Site 3.12.1
into every effective model. The Java adapter can omit only that generated build
root after proving its origin, unchanged default configuration/bindings, absence
of source/parent/profile/management/report/extension declarations and absence
from complete clean/default lifecycle and current invocation plans, including
forks. Unknown Maven versions, commands, metadata or failed planning retain the
full plugin audit. The effective-POM Python preflight only recognizes a candidate;
it cannot authorize the omission. No vulnerability suppression is added. A Site
dependency reached through another plugin is still scanned. See the adapter
README for the exact scope contract and tests.

`--plan` prints a nonsecret command without calling Maven or any analyzer.
`--verify-effective path.xml` validates an existing actual Maven model without
calling a provider. Neither mode constitutes an authenticated vulnerability audit.


## Standalone issuer audit

After installing this reactor, run the same command with `--standalone-project keycloak`
under JDK 21. This accepts only the fixed repository path
`infrastructure/keycloak/security-generation/pom.xml`, the reviewed GAV and Java
release 21. It performs one nonrecursive full audit with the same fail-closed policy,
public cache and environment-only credentials. The backend lifecycle audit, all three
bootstrap audits and this issuer audit are separate required AND conditions in CI.
No complete audit is claimed from local Trivy inventory alone.

Effective policy validation also rejects unpinned reporting plugins and unreviewed
build extensions. Unused managed plugins do not activate release automation.
