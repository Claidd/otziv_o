# C52: libxslt refresh of nginx and the web image

C52 answers the fix-availability release gate of 11 November 2026: Alpine published
fixed `libxslt` packages for CVE-2026-87636 and CVE-2026-9947 (both HIGH in the
scanner report of quality run 42981424911), so the accepted
nginx image and the built web image blocked every release.

## What changes

- **Reviewed nginx image** (`compose.yaml`, service `nginx`). The C52 recipe starts
  from the active C50 digest and installs only the official Alpine 3.24
  `libxslt` **1.1.43-r4**. The build fails unless the parent carries 1.1.43-r3, every
  other installed package keeps its version, and the nginx payload (configuration,
  modules, executable, entrypoint scripts: bytes, modes and owners) is unchanged.
  `nginx -t` runs in the build. The parent preserves the earlier musl, zlib, TLS and
  curl fixes.
- **Web image** (`frontend/Dockerfile`). Its Alpine 3.21 base takes the official
  `libxslt` **1.1.42-r3**, pinned next to the existing curl and Brotli pins and
  checked in the build. The quality workflow checks the shipped version. The image
  is built by CI on every release and needs no publication step.

No application, configuration or other package changes. MySQL, PostgreSQL, Keycloak,
Node, phpMyAdmin, Certbot and the monitoring images keep their accepted pins: the
gate reported `libxslt` only for nginx and the web image.

## Activation and retained evidence

`reviewed-images-c52-nginx.json` selects the single committed recipe
`builds/c52-nginx/Dockerfile`. `proofs/c52-nginx/` retains the authenticated
publication, the independent anonymous pull and the activation candidate, as for
C49 and C50. The active index and `compose.yaml` point to the checked immutable
digest. Publication, anonymous digest pull, retained OCI provenance/SBOM and fresh
scans remain mandatory; nothing in the release gate is relaxed.

Relevant contracts: `reviewed-image-sets-c52.test.mjs` and the existing publication,
activation and runtime security suites.

## Runtime verification

The recipe and the web image were built locally and scanned with
`infrastructure/runtime-security/scan.mjs`: both report zero HIGH and CRITICAL
findings. The local stand runs the rebuilt web image. Release through
`deploy.ps1 -ImageTransport Direct` follows successful CI for the exact merged main.
