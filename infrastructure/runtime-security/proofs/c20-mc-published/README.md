# C20: local S3 initializer OS refresh

The Compose local-only `minio-init` image contained libevent 2.1.12 and OpenSSL
3.5.5. CI detected six fixed libevent HIGH findings and one OpenSSL HIGH finding.
The C20 recipe retains the exact C15 `mc` binary and corresponding source archive,
refreshes the UBI runtime to the recorded package lock, and removes the package
manager and curl as before. Libevent is 2.1.13-1.el9_8 and OpenSSL is 3.5.8-1.el9_8.

Publication run [35507543392](https://github.com/Claidd/otziv_o/actions/runs/35507543392)
binds the recipe, binary inventory, SBOM and registry provenance. The published
image's raw scan has zero HIGH/CRITICAL findings, without new suppressions.
Anonymous manifest verification and a fresh pull both passed.

`compatibility/result.json` records 36 checks against the currently pinned local
Versity image: repeatable initialization, public and private object permissions,
versioned reads, copying, restart durability and owned-resource cleanup. It ran
against the published digest at the actual local client memory limit (384 MiB).
Only `compose.prod-local.yaml` changes runtime reference; the VPS was not deployed.
