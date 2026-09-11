# Build-support reactor

This finite reactor builds the reviewed downstream audit adapter, Site plugin and
Testcontainers transport. Each module has its own POM; the Site derivative has no
Apache Maven parent. An identical
explicit `pluginManagement` policy in each POM pins the effective build tools;
`plugin-policy.json` and `audit.py` reject missing, changed or unreviewed active
plugins. The policy is an inventory and compatibility guard, not a vulnerability
exception or a claim that every upstream artifact is currently clean.

Build with JDK 26 and Maven 3.9.15 before building the backend:

```sh
mvn -B -ntp -f backend/build-support/pom.xml install
python -m unittest discover -s backend/build-support -p test_audit.py
```

The application, reactor and standalone issuer audits must all succeed in the same
authenticated CI job. Run each even if an earlier audit reports vulnerabilities, and
combine their exit statuses. Each of the four reactor projects uses one direct goal rather than the
backend `security-audit` lifecycle, avoiding a duplicate application audit:

```sh
python backend/build-support/audit.py \
  --maven "$GITHUB_WORKSPACE/backend/mvnw" \
  --settings "$HOME/.m2/settings.xml" \
  --suppression-file "$GITHUB_WORKSPACE/infrastructure/runtime-security/maven-false-positives.xml" \
  --data-directory "$RUNNER_TEMP/otziv-dependency-check-data"
```

The caller supplies `SONATYPE_GUIDE_TOKEN` and `SONATYPE_GUIDE_USERNAME` only through
the approved environment. Maven settings must refer to that token environment
variable, as the repository's `setup-java` configuration does. Credentials never
appear in the command, manifest or public cache. Only the existing public database
and `oss_cache` paths should be cached. Authentication, remote errors, CVSS 7+ and
missing reports remain blocking; the OSS Index package cache stays at 24 hours.
No analyzer is disabled.

Before the audit the runner obtains the actual complete Maven effective reactor
and validates every active plugin against the policy. It enables application,
test, provided, runtime, system and plugin dependency scanning explicitly. All
nineteen CLI properties were checked against the built adapter's inherited Mojo
descriptor. This includes `odc.plugins.scan`; the superficially similar
`scanPlugins` CLI property does not bind the upstream parameter.

The runner audits all four installed projects sequentially with `-N`, after one
complete effective-model preflight. It continues through every project even if
one reports vulnerabilities, retaining the first failure exit code. This avoids
Maven skipping dependent projects after a failed reactor module. The shared cache
remains active for each invocation. `target/audit-report-coverage.json` records each
project's actual exit code, report presence and digest. Missing or malformed reports cannot turn a
zero process exit into complete coverage. A failed/partial reactor audit remains
failed; the runner never repeats an already audited project to hide its failure.

Upload each project's `target/dependency-check-report.json` and `.html`, plus the
aggregate `target/audit-policy-verification.json` and `audit-report-coverage.json`.
Preserve raw reports before changing dependency graphs or audit policy. The local
fixture tests exercise token rejection, command bindings, exact reactor coverage,
effective-version/override drift, unknown plugins, cache isolation, nonsecret plan
mode, failed audit propagation and rejection of stale/incomplete report sets.

`--plan` prints a nonsecret command without calling Maven or any analyzer.
`--verify-effective path.xml` validates an existing actual Maven model without
calling a provider. Neither mode constitutes an authenticated vulnerability audit.


## Standalone issuer audit

After installing this reactor, run the same command with `--standalone-project keycloak`
under JDK 21. This accepts only the fixed repository path
`infrastructure/keycloak/security-generation/pom.xml`, the reviewed GAV and Java
release 21. It performs one nonrecursive full audit with the same fail-closed policy,
public cache and environment-only credentials. The backend lifecycle audit, all four
bootstrap audits and this issuer audit are separate required AND conditions in CI.
No complete audit is claimed from local Trivy inventory alone.

Effective policy validation also rejects unpinned reporting plugins and unreviewed
build extensions. Unused managed plugins do not activate release automation.
