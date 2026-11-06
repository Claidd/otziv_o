# C50: musl and Go x/net refresh

C50 updates eleven reviewed image components while retaining the existing
fix-availability release gate and daily production-image security workflow.
Publication, anonymous digest pull, retained OCI provenance/SBOM, exact activation
evidence and fresh main scans remain mandatory.

## Fixed libraries

- nginx, phpMyAdmin, Grafana, local S3 and MC use Alpine 3.24 `musl` and
  `musl-utils` **1.2.6-r3**. Certbot keeps Alpine 3.23 and takes **1.2.5-r24**.
  These are the distribution fixes for
  [CVE-2026-107891](https://security.alpinelinux.org/vuln/CVE-2026-107891).
- Prometheus/promtool, Loki, Tempo, Alloy, Grafana, Dozzle, MC and the local S3
  gateway are rebuilt with **golang.org/x/net v0.62.0**, fixing
  [GO-2026-6860 / CVE-2026-78661](https://pkg.go.dev/vuln/GO-2026-6860).
  Required `x/*` changes are retained in the build evidence. Guarded monitoring
  recipes compare the complete expected root module changes; Grafana also keeps
  its workspace and nested module files unchanged.

The package steps compare unrelated installed package versions and protected
application/configuration hashes and file metadata before and after installation.
Go builds retain the reviewed upstream source, build tags, entrypoints and runtime
parents. The parents preserve the earlier zlib, TLS, curl, PHP and Composer fixes.

Grafana is identified as `12.4.10+otziv.6`. Its new executable SHA-256 and complete
canonical Go build-info hash are bound in
`infrastructure/runtime-security/adjudications/grafana-tempo-c50/identity.json`.
The existing two Tempo pseudoversion corrections are checked against the same
reviewed fixed source and module checksum. Regression tests verify that this
cannot exempt x/net or an additional CVE. Alloy retains the existing actual
function/import-closure checks for its two Docker daemon-only findings.

## Activation and retained evidence

Each `reviewed-images-c50-<component>.json` selects exactly one committed recipe.
`proofs/c50-<component>/` retains the authenticated publication, independent
anonymous pull and the activation candidate. The active index and Compose files
point to the checked immutable digests. MySQL, PostgreSQL, Keycloak and Node keep
their accepted pins.

`c50-refresh.mjs` additionally checks the actual scanned package inventory:
both musl packages and every affected Go executable must be present with the
fixed versions, including both Prometheus binaries. Raw report hashes, image
identity, unmodified findings and recalculated verdicts must match publication.
Existing exact-binary checks run before accepting Grafana or Alloy. Other C50
components require raw zero HIGH/CRITICAL findings. The public byte evidence and
C50 manifests/identity have explicit Git attributes for Windows/Linux checkout.

Monitoring CI selects the published C50 images for runtime checks, so a later
test cannot accidentally rebuild an older monitoring recipe.

## Runtime verification

The existing synthetic-volume upgrade harness was run against all five published
monitoring candidates. It verifies stored data, newly written data after upgrade,
restart, configuration/health commands and rollback using the pre-upgrade backup.
Grafana also verifies stored datasource-secret decryption; Alloy verifies cursor
continuity without replaying the original input. Local S3/MC pass object,
versioning, error and restart compatibility checks.

A separate browser fixture verifies Grafana's save cancellation, confirmed save,
delete cancellation and confirmed deletion against consistent copies of its
SQLite database. Grafana 12's UI writes dashboard resources into the physical
`resource` table; the check covers this and the legacy `dashboard` table. The
fixture uses generated credentials and its own labelled container/network/volume.

The full local stack is verified with
`infrastructure/scripts/local/prod-like-smoke.ps1`, a fresh VPS DB restore and
observability enabled. Outbound client messages and live banking operations stay
disabled locally. Release through `deploy.ps1 -ImageTransport Direct` follows
successful CI for the exact merged main and includes the mandatory encrypted
database backup, selective rollout and actual VPS verification.

Relevant contracts: `c50-refresh.test.mjs`, `grafana-tempo-c50.test.mjs`,
`monitoring-source.test.mjs` and the existing publication, activation and runtime
security suites. Per-run operator evidence remains outside the release source
in `.codex-tmp/security-c50/` and `diagnostics/security-c50-*.log`.
