# PostgreSQL C16: PCRE2 source replacement

The currently deployed PostgreSQL 17.11 runtime contains Debian PCRE2 10.46.
Debian's tracker reports CVE-2026-86145 and CVE-2026-89161 without a trixie fix.
The candidate builds upstream PCRE2 10.48, which contains both fixes. The engine,
glibc, locales, entrypoint, database volume contract and all unrelated runtime
files remain identical to the published C14 image.

This is an actual shared-library replacement, with a real Debian package,
source/archive hashes, license, file inventory and SBOM. It does not rename an
old binary or hide raw scanner findings. `make check` runs POSIX, JIT, general
matching and grep suites in the build. The retained source files show the DFA
workspace bounds check and the JIT copied-subject cleanup from upstream.

`postgres-c16-adjudication.mjs` validates the exact executable inventory, source
and package identities, complete non-log file graph and container contract.
It checks the unchanged parent graph before reusing the historical component
reviews. Only the two named CVEs on this exact PCRE2 build receive the
`fixed_upstream_source` decision. New findings and changed binaries still fail.
The old C14 publication proof and validator remain unchanged.

Local evidence: upstream suites pass, 21 database continuity checks pass,
5 TCP/authentication checks pass, and exact-runtime scanner reassessment passes.
The normal `c16-postgres` workflow published the exact candidate with verified
provenance/SBOM and anonymous download. Independent stopped-image inspection
and 39 checks on a fresh copy of the current 102-table Keycloak database passed,
including existing-password login and rollback preserving post-upgrade writes.
The source defaults now select that accepted digest. Production cutover still
requires a fresh authenticated backup and a coordinated database procedure;
the ordinary deployment image-change guard remains in force.

Sources:

- https://github.com/PCRE2Project/pcre2/releases/tag/pcre2-10.48
- https://github.com/PCRE2Project/pcre2/commit/c932e70451eafef922ebef364ac25042f0031135
- https://github.com/PCRE2Project/pcre2/pull/937
- https://security-tracker.debian.org/tracker/CVE-2026-86145
