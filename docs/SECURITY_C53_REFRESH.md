# C53: apache2 refresh of the phpMyAdmin image

C53 answers the fix-availability release gate of 12 November 2026: Alpine published
`apache2` **2.4.71-r0** for CVE-2026-119618 (CRITICAL, remote code execution in
`mod_http2`; scanner report of quality run 43282893221), so the accepted
phpMyAdmin image blocked every release.

## What changes

- **Reviewed phpMyAdmin image** (`compose.yaml` and `compose.prod-local.yaml`, service
  `phpmyadmin`, profile `db-admin`). The C53 recipe starts from the active C50 digest
  and installs only the official Alpine 3.24 `apache2` **2.4.71-r0**. The build fails
  unless the parent carries 2.4.69-r0, every other installed package keeps its version,
  and the phpMyAdmin payload is unchanged: the application, the phpMyAdmin, PHP and
  Apache configuration, the PHP runtime, the entrypoint and the local scripts (bytes,
  modes and owners). `apachectl configtest` and `httpd -v` (2.4.71) run in the build.
  The parent preserves the earlier musl, PHP and application fixes.

No application, configuration or other package changes. MySQL, PostgreSQL, Keycloak,
nginx, Node, Certbot and the monitoring images keep their accepted pins: the gate
reported `apache2` only for phpMyAdmin. The web and backend images do not carry Apache.

phpMyAdmin is started only with the `db-admin` profile; an ordinary production
rollout does not run it, so activating C53 restarts no production service.

## Activation and retained evidence

`reviewed-images-c53-phpmyadmin.json` selects the single committed recipe
`builds/c53-phpmyadmin/Dockerfile`. `proofs/c53-phpmyadmin/` retains the authenticated
publication, the independent anonymous pull and the activation candidate, as for
C50 and C52. Publication, anonymous digest pull, retained OCI provenance/SBOM and fresh
scans remain mandatory; nothing in the release gate is relaxed.

Relevant contracts: `reviewed-image-sets-c53.test.mjs` and the existing publication,
activation and runtime security suites.

## Runtime verification

The recipe was built locally and scanned with
`infrastructure/runtime-security/scan.mjs`: zero HIGH and CRITICAL findings. The
build log shows the single upgrade `apache2 (2.4.69-r0 -> 2.4.71-r0)`, `Syntax OK`
and `Server version: Apache/2.4.71`. Release through
`deploy.ps1 -ImageTransport Direct` follows successful CI for the exact merged main.

GitHub Actions run [43306909429](https://github.com/Claidd/otziv_o/actions/runs/43306909429) published and independently pulled
`ghcr.io/claidd/otziv-security@sha256:e6a68833d01e2a161f21565570659067bfd4a90d7af9817bac0eb82d5e6ee03d`. The production and local db-admin profiles use this digest.
