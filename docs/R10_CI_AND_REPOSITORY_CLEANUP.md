# R10 CI and repository cleanup runbook

## Required checks

The `Quality gates` workflow runs on every pull request and push. After one successful run on the default branch, configure branch protection to require these stable checks:

- `Quality gates / Repository contracts`
- `Quality gates / Backend (full Testcontainers suite)`
- `Quality gates / Frontend (unit and production build)`
- `Quality gates / Mobile web (unit and production build)`
- `Quality gates / WhatsApp gateway (unit)`
- `Quality gates / External review worker (syntax and health)`
- the existing secret-scan and SQL-injection-guard checks

Keep `Dependency audit` required only for changes to dependency manifests and lockfiles. It also runs weekly and manually. Production dependencies at moderate severity or above are blocking. The complete development-dependency audit is visible in logs but is report-only until its existing findings are resolved; do not use `npm audit fix --force`.

The backend job runs the complete Maven `verify` lifecycle on a Docker-capable runner. `OtzivOApplicationTests` starts MySQL with Testcontainers and runs Flyway against a fresh database. The repository-contract job separately rejects changes, deletions and renames of migrations that already exist on the comparison base; new uniquely versioned migrations remain allowed.

## Safe cleanup sequence

Do not combine the following cleanup with application behavior, dependency upgrades or release work.

1. Preserve a repository bundle and verify it can be cloned. Record the default-branch commit and active release tags.
2. Move Android APK/AAB delivery to a signed CI artifact or release asset. Update and verify every `mobile/builds` lookup in the production deployment scripts before untracking any APK.
3. Move generated media that must be retained to explicitly owned object/release storage. Confirm no application, deployment script or operator process reads it from the checkout.
4. In a cleanup-only pull request, use `git rm --cached` for confirmed generated files. Do not delete local working copies and do not touch `.release-worktree-*` directories. The new ignore rules prevent accidental re-addition.
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
