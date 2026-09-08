# R10 CI and repository cleanup runbook

## Required checks

Read-only GitHub API verification on 2026-09-07 found `Claidd/otziv_o` branch `main` **unprotected** at revision `3458e56f29ec0288925f2a4795105fe863da5158`: `protected=false`, the branch summary's required checks were disabled/empty, and both repository rulesets and effective branch rules returned HTTP 200 with empty arrays. The detailed protection endpoint required authentication (HTTP 401), but the public branch/rules evidence already establishes the current absence of enforcement. This is an open configuration defect, not merely missing local evidence. The observations are retained in `.codex-tmp/remediation-completion-20260907/github-main-branch-public.json`, `github-main-rules-public.json`, and independently reproduced by `github-required-check-verification.json`. No remote settings were changed.

Authenticated owner-browser inspection later on 2026-09-07 confirmed that neither classic branch protection nor rulesets exist. Git Credential Manager sign-in was subsequently completed through its device flow; credentials remain in its normal Windows store. Repository and environment secret lists were empty at inspection, including the required Sonatype token. These are separate observations from publishing or passing any new workflow.

The user subsequently completed Sonatype onboarding on the Free plan and created `otziv-dependency-audit`. After explicit confirmation, `SONATYPE_GUIDE_TOKEN` was added as an encrypted repository Actions secret; GitHub displayed `Repository secret added.`. The token expires on October 8, 2026 as displayed by Sonatype. The initial usage page showed 0 of 500 credits used for September 7–October 6. No paid subscription was created. Replace the expiring token through Sonatype and update the same repository secret before that expiry; never commit its value or copy it into issue/PR bodies. Secret setup alone does not establish a successful authenticated analyzer run.

The later hosted [C2 audit](https://github.com/Claidd/otziv_o/actions/runs/34154946485) and [C3 audit](https://github.com/Claidd/otziv_o/actions/runs/34156195253) both completed authenticated Sonatype analysis and failed at the configured vulnerability threshold. C3 restored the public analyzer cache saved by C2; its NVD and Sonatype stages completed without authentication or quota errors. The owner then observed cumulative Free usage of 60.9/500 credits and 609 requests, displayed as 12%, at approximately 19:52 UTC. A cache hit is not proof of zero cost. This closes the missing-authenticated-result prerequisite, while the dependency gate remains failed. Exact commits, unchanged reports, provider counts and subsequent narrow corrections are recorded in the [hosted Maven audit](../infrastructure/runtime-security/MAVEN_HOSTED_AUDIT_2026-09-08.md) and its public hash manifest. Default 24-hour cache expiry, full audit scope and fail-closed behavior remain in force; no paid setup or risk acceptance was added.

The verified snapshot was published as commit `25579270c5494eedb6f3badc0ad3737b041ba9d6` on `codex/architecture-remediation-20260907`, and [draft PR #2](https://github.com/Claidd/otziv_o/pull/2) is open. Actual pre-commit and pre-push hooks passed; the publication worktree is clean and all 4633 tracked file blobs match the commit. The F01–F20 registry now references this PR. Hosted checks have started; their successful completion and branch enforcement are not yet claimed.

The `Quality gates` workflow runs on every pull request and push. Classic branch protection can be enabled while required checks fail: its purpose is then to block the merge. Bind the exact check-run names in `expected-branch-policy.json` to their independently verified GitHub Actions application identity from the explicitly reviewed published PR revision. GitHub may display a workflow prefix in its UI; do not add that prefix to an API context unless it is present in the actual check-run name. The inventory is:

- `Quality gates / Repository contracts`
- `Quality gates / Backend (full Testcontainers suite)`
- `Quality gates / Issuer security generation (real Keycloak and PostgreSQL)`
- `Quality gates / Frontend (unit and production build)`
- `Quality gates / Mobile web (unit and production build)`
- `Quality gates / WhatsApp gateway (unit)`
- `Quality gates / External review worker (syntax and health)`
- `Quality gates / Built web and mobile browser scenarios`
- `Quality gates / Android native tests, lint and debug assembly`
- `Quality gates / Shared client package and compatibility fixtures`
- every `Quality gates / Built integration image (...)` matrix check (backend, web, worker, whatsapp, observer, publisher)
- `Dependency audit / Dependency audit gate`
- `Quality gates / Upstream image security gate`
- the existing secret-scan and SQL-injection-guard checks

Require the stable `Dependency audit gate`, not the conditional matrix jobs. The dependency workflow starts on every PR and on pushes to `main`; a feature-branch push is not audited a second time before its PR run. Its change-detection job runs the expensive audits for manifests/locks, shared package, Maven wrapper, npm configuration and CI changes; schedule/manual/unknown-base runs audit everything. The final gate always runs and accepts skipped jobs only when successful change detection explicitly requested no audit. Failure, cancellation or unexpected skip fails the gate. This prevents a path-filtered workflow from leaving a required check permanently absent. The complete npm audit still blocks at low severity and production-only at moderate; the browser runner is included alongside the four application graphs. No threshold is reduced.

Repository contracts also execute the actual isolated-Git release lineage regressions in `check-deploy-release-contract.ps1`: ancestry, exact prepared revision, post-validation mutation and fetch-failure behavior. Workflow syntax and source tests do not prove that server-side branch protection/rulesets are enabled. Before changing branch policy, inspect the repository's actual rules, verify the check identities against the reviewed immutable PR source, and record the observed application/check/run identifiers with policy evidence. Successful CI and actual server enforcement are separate acceptance conditions. The local remediation does not silently modify those remote settings.

The proposed inventory remains exactly 20 checks with strict status checks, administrator enforcement and disabled force pushes/deletions. The v2 configuration in `infrastructure/scripts/security/expected-branch-policy.json` names the binding source explicitly: published C9 commit `7141d947ef6b6172c76fdbb9f297895fda107e60`, PR #2, branch `codex/architecture-remediation-20260907`. It records the reviewed workflow path for each check name. It contains no guessed application, job, check-run or workflow-run IDs. The configuration is still a draft requiring identity verification and policy review; writing it does not protect `main`.

`verify-branch-policy.mjs` emits `otziv-required-check-observation-v2` with three independent results:

- `identity`: the immutable binding commit belongs to the specified published PR in the expected repository/base/source branches. Required jobs come from exactly one matching `pull_request` workflow run per reviewed workflow path and its observed run attempt. Each job's exact API `check_run_url` must resolve within the same repository's exact-head check-run inventory and match its name, suite and GitHub Actions application. The application ID is independently read from `GET /apps/github-actions`, including its official owner and application URL. A failed, skipped or active check can prove its identity; it cannot prove successful acceptance. A historical identity is not invalidated merely because its completion is older than seven days.
- `serverPolicy`: detailed classic protection actually requires those exact `{context, app_id}` pairs, strict checks and the unchanged administrator/force-push/deletion settings. Current check conclusions do not turn an installed policy into an unprotected branch. Unavailable detailed protection stays unverified. Ruleset-only protection remains `RULESET_ONLY_NOT_EVALUATED`; this verifier does not assess ruleset bypass semantics.
- `candidateAcceptance`: when an explicit candidate SHA is provided, it must be the current open PR head before and after the reads. Every required check must have an unambiguous trusted PR run/job association and a completed success no older than seven days, with a valid non-future completion time. Failure, skip, cancellation, missing or ambiguous evidence blocks this result. Without a candidate SHA it is `NOT_EVALUATED`. `ALL_REQUIRED_CHECKS_PASSED` covers the required CI checks only; it is not a merge, production activation or blanket release approval.

The read-only verifier uses bounded GET pages for PR commits, exact-head PR runs, check runs and attempt-specific jobs (100 entries per page, at most three pages). It reads each selected run again to reject an attempt/suite/source change. A successful push or manual check with the same name cannot replace a failing PR job. Multiple matching PR runs or matching required jobs remain ambiguous; it does not select the successful result. An incomplete/oversized page, foreign repository/application/revision, unverified origin, malformed association or changing PR candidate head fails closed. This deliberately conservative model may require a complete, unambiguous rerun after partial reruns; it never borrows a convenient result from another attempt.

For a policy-only observation and proposed request body:

```sh
node infrastructure/scripts/security/verify-branch-policy.mjs \
  infrastructure/scripts/security/expected-branch-policy.json \
  /secure/evidence/github-required-check-policy.json \
  --require enforcement
```

For independent candidate acceptance together with actual enforcement:

```sh
node infrastructure/scripts/security/verify-branch-policy.mjs \
  infrastructure/scripts/security/expected-branch-policy.json \
  /secure/evidence/github-required-check-acceptance.json \
  --candidate-head "$CANDIDATE_SHA" --require both
```

The default CLI requirement is `both`; `--require enforcement` must be explicit to return exit 0 for installed protection while checks fail or acceptance is unevaluated. `--require acceptance` checks only the required CI outcomes and does not establish branch protection. Exit 2 means the explicitly selected requirement is not satisfied, and exit 1 means invalid input or a failed observation. V1 configuration is rejected with an explicit migration error; old aggregate status semantics are not silently reused. Every output prints the three statuses separately. `readyForPolicyReview` and the generated request body depend on verified identities, not on successful conclusions; they never claim that the body was applied.

All server requests remain GET-only. No PUT/PATCH, check-run creation, dispatch, rerun, artifact or log download is implemented. An optional `OTZIV_GITHUB_READ_TOKEN` may provide access to detailed policy; its value is never recorded. Review the proposed body against the complete current server settings before a separately authorized application, preserving any stronger existing checks, reviews, restrictions or organization policies. Re-read the applied policy afterward. The draft does not invent review-count/CODEOWNERS requirements. P21 enforcement remains incomplete until that actual server-side readback is evidenced, even when all local tests pass. Conversely, installing protection with failed CI does not satisfy release acceptance.

The backend job runs the complete Maven `verify` lifecycle on a Docker-capable runner. `OtzivOApplicationTests` starts MySQL with Testcontainers and runs Flyway against a fresh database. The repository-contract job separately rejects changes, deletions and renames of migrations that already exist on the comparison base; new uniquely versioned migrations remain allowed.

After verify, the backend job runs `FinancialScenarioBenchmark` with four iterations and enabled SQL budgets against isolated synthetic MySQL data. Response shape, transaction rollback and query budgets are blocking assertions; elapsed times remain measurements, not an invented performance SLA. Its JSON scenario evidence is uploaded separately. The built publisher job tests real TLS collection/publication inside the image with network disabled, then runs the same complete vulnerability-report gate as other images.

## Safe cleanup sequence

Do not combine the following cleanup with application behavior, dependency upgrades or release work.

The current source/workstation recovery boundary is documented in
`docs/DISASTER_RECOVERY_SOURCE.md`.

1. Preserve a repository bundle and verify it can be cloned. Record the default-branch commit and active release tags.
2. Move Android APK/AAB delivery to a signed CI artifact or release asset. Update and verify every `mobile/builds` lookup in the production deployment scripts before untracking any APK.
3. Move generated media that must be retained to explicitly owned object/release storage. Confirm no application, deployment script or operator process reads it from the checkout.
4. In a cleanup-only pull request, use `git rm --cached` for confirmed generated files. Before removing any future `.release-worktree-*`, verify that its HEAD and dirty/untracked contents are preserved, then remove it with `git worktree remove`; never commit a worktree path as a gitlink. The root ignore rules prevent accidental re-addition.
5. Handle ambiguous root files only after an owner approves each path. Use repository search plus a production/deploy smoke test before removing it from the index.
6. Run the quality workflow and `./infrastructure/scripts/local/prod-like-smoke.ps1` before merging the cleanup pull request.
7. Treat history rewriting as a separate, explicitly approved maintenance operation with a write freeze, backup, collaborator coordination and fresh-clone verification. Current-index cleanup alone does not shrink old Git packs.

## Dependency upgrade lanes

Use one bounded lane per pull request and keep lockfile changes project-local:

1. compatible patch updates within an Angular toolchain, keeping Angular packages on one patch line;
2. WhatsApp gateway dependencies with unit tests and a QR/session/webhook smoke test;
3. external review worker runtime/dependencies with Chromium, OCR and health checks;
4. Spring Boot/JDK platform changes independently from AWS, OpenAI and other library upgrades.

For every lane, capture the pre-upgrade dependency audit, update without force, run its dedicated quality job and the prod-like smoke, then compare the resulting dependency tree and artifact behavior. Do not mix a Node major-runtime migration with unrelated library upgrades.
