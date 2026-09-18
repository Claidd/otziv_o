# MySQL libevent security refresh, 18 September 2026

The previous C17 MySQL image contains libevent 2.1.12-8.el9_4, which the mandatory
runtime scan rejects with eight fixable HIGH findings. C18 updates only libevent
to 2.1.13-1.el9_8. MySQL remains 9.7.3; the server binary and the complete package
inventory excluding libevent match the source. No vulnerability suppression or
security-check bypass is introduced.

Publication set `c18-mysql` uses the exact deployed C17 image as its base. CI
publishes provenance and an SBOM, scans the immutable image with zero HIGH or
CRITICAL findings, then verifies a fresh anonymous digest pull. Retained evidence
is under `infrastructure/runtime-security/proofs/c18-mysql`.

`mysql-libevent-rehearsal.py` restores a private fresh production snapshot into an
isolated source image, starts the candidate on the same disposable volume, and
compares every table's row count/checksum, schema, migration history, accounts and
settings. It also verifies login, new writes across restart, rollback to the old
image without losing those writes, and a fresh dump restore. Test containers have
no network or published ports and are removed with their owned volumes. Only
hashes and counts enter the evidence; the database dump remains private.

The C18 readiness validator replays the retained C17 acceptance, binds the new
rehearsal and publication to exact hashes, and preserves the coordinated-activation
requirement. The ordinary deployment guard still refuses changing a running
database's image or storage. Its native-launch allowance now identifies the
reviewed C18 image for use after the separately coordinated activation.

Activation requires the deployment lock, paused self-heal, stopped application
writers, disabled database events, a verified encrypted backup copied off host,
and unchanged data fingerprints before writers resume. Keep the original image,
Compose file and guard for rollback. A package-only refresh does not authorize an
engine upgrade or schema migration. The owner authorized fixing the vulnerability
and the database restart in the current maintenance session.

Application deployment must still pass protected main CI through `deploy.ps1`.
