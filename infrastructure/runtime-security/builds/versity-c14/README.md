## C14 local S3 replacement

This candidate is for the local test environment only. It uses the maintained
Apache-2.0 VersityGW **v1.8.0** release (2026-09-04), published as
`ghcr.io/versity/versitygw@sha256:30292fc2eeacc67a36993b01f7a7a5e3361a19cced0e80c1d71cfa2a4b0a2499`.
The derivative updates only Alpine's `libssl3` and `libcrypto3` from `3.5.7-r0`
to **`3.5.8-r0`** using the vendor's normal package transaction. Alpine's security
database assigns CVE-2026-14456 to that fixed version. It changes no Versity source
or binary.

The unchanged gateway binary has SHA-256
`a44bb13582e9f62da5cf6f0c24285b7f575c68cab2959e12e547d3841c172bc5`.
`runtime-apk.lock` locks the complete resulting installed package inventory.
Every build checks both that lock and the original gateway binary hash. A vendor
repository change fails the build; future availability of those exact packages
remains an external dependency. The actual APK database remains available to
scanners. No CVE is ignored, adjudicated away, or cosmetically renamed.

The source service/inventory ID remains `minio` for compatibility with existing
local `http://minio:9000` URLs. The implementation is Versity. Publication set
`c14-local-s3` must preserve this explicit replacement mapping. This work does
not change the VPS S3 provider and must not be interpreted as a VPS migration.

## Local configuration to integrate

Use a **new `versity_data` named volume**. Keep the old `minio_data` volume intact;
the on-disk formats are not interchangeable. There is no in-place migration in
this proof. Example service settings, with the reviewed published derivative
digest supplied by the publication workflow:

```yaml
minio:
  image: ghcr.io/claidd/otziv-security@sha256:<published-derivative-digest>
  entrypoint: ["/bin/sh", "-ec"]
  command:
    - |
      mkdir -p /var/lib/versity/data /var/lib/versity/versions /var/lib/versity/iam
      exec /usr/local/bin/versitygw --port :9000 --health /health --iam-dir /var/lib/versity/iam posix --versioning-dir /var/lib/versity/versions /var/lib/versity/data
  environment:
    ROOT_ACCESS_KEY: ${S3_ROOT_USER}
    ROOT_SECRET_KEY: ${S3_ROOT_PASSWORD}
  volumes:
    - versity_data:/var/lib/versity
  healthcheck:
    test: ["CMD", "wget", "-q", "-T", "2", "-O", "/dev/null", "http://127.0.0.1:9000/health"]
    interval: 10s
    timeout: 3s
    retries: 12
```

The normal `mc alias set`, `mc mb --ignore-existing`, and `mc anonymous set
download` initialization remains compatible. The `/health` route reserves that
bucket name. No web UI or admin listener is needed for the tested S3 interface.
Gateway credentials come from environment variables, not command arguments.

`data/` holds current objects, `versions/` non-current versions, and `iam/` durable
IAM state. Versity's POSIX backend uses filesystem metadata, including extended
attributes. A Linux Docker named volume was tested; this is not a claim that
Windows host bind mounts preserve those semantics. Any future physical backup
must preserve all three directories and their metadata coherently. Only S3 copy
restoration and restart persistence are established here.

## Evidence and limits

The proof runner accepts an immutable server image, immutable mc image, and a
new output directory:

```text
node infrastructure/runtime-security/builds/versity-c14/run-compatibility.mjs <server-digest-or-local-ID> <mc-digest-or-local-ID> <new-output-directory>
```

It enforces local Docker, creates a fresh owner-labelled internal network and
volume, publishes no ports, generates credentials in memory, and removes only
its own resources. The 36 checks cover initialization twice, public anonymous
GET versus denied private GET, authenticated reads/writes, two distinct opaque
version IDs, reads of both old/current versions, S3 copy restoration, and the
same IDs/bytes after a graceful server restart. The frozen mc source is unchanged.

The server is capped at 768 MiB and 2 CPUs in the proof; this cap is not a
minimum-capacity recommendation. A post-restart idle sample is retained, not a
load benchmark. `mc pipe` was OOM-killed at a 192 MiB client limit. The successful
profile uses **384 MiB and `GOMEMLIMIT=256MiB`** for the separate client container;
the same binary is used in both profiles. The negative process state is retained.

The official Versity POSIX versioning page still labels versioning experimental.
That is an explicit local-test-only tradeoff, not an assertion of production
version-retention guarantees. There is no distributed storage, outage recovery,
MinIO volume conversion, or production acceptance proof in this package.

Raw before/after scans, SBOMs, package/binary hashes, the OOM negative control,
runtime results and primary source bytes are retained in
`../../proofs/c14-versity/`. Publication must rebuild the exact committed recipe,
scan the actual registry image, verify provenance/SBOM, and replay compatibility.
Local results alone do not activate the replacement.

The source freeze uses LF endings, matching a clean Git checkout. An additional
cached build from this canonical Dockerfile reproduced the same image
configuration digest, root filesystem and gateway binary. Its OCI index changed
because BuildKit generated a new local attestation. `lf-rebuild.json` records this
comparison; the earlier raw scan and 36-check runtime proof remain byte-for-byte
unchanged. The historical executed runner is retained alongside the proof; its
only difference from the canonical runner is CRLF versus LF. These local build
receipts are not a claim of authenticated publication from their local Git HEAD.

Primary sources:

- [Official v1.8.0 release](https://github.com/versity/versitygw/releases/tag/v1.8.0)
- [Official Docker images and configuration](https://github.com/versity/versitygw/wiki/Docker)
- [POSIX versioning and its experimental status](https://github.com/versity/versitygw/wiki/POSIX-versioning)
- [Health endpoint semantics](https://github.com/versity/versitygw/wiki/HealthCheck)
- [Alpine v3.24 security database](https://secdb.alpinelinux.org/v3.24/main.json)
