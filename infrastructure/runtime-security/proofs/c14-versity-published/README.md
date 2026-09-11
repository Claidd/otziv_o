# Published local S3 candidate

VersityGW v1.8.0 with the two official Alpine package updates is published as
`ghcr.io/claidd/otziv-security@sha256:3bd06e6605d318c852fc56f42d14316b5cd74ed233119d586b078bd4a5e4be21`.
Its legacy publication component is `minio`, set `c14-local-s3`.

Publication job 102105777000 and anonymous-download job 102106603877 succeeded
in [workflow run 34238888111](https://github.com/Claidd/otziv_o/actions/runs/34238888111),
attempt 1, exact commit `135747397491454711da71155d50a440fb4d7c97`.
The overall workflow still contained unrelated queued/running work at this
snapshot; this proof claims only those two publication jobs.

Both downloaded artifact ZIPs matched GitHub API SHA-256 digests. The 26 extracted
artifact files are preserved without newline normalization. Validation replays
the publication/anonymous OCI descriptor and provenance chain, source manifest,
and mandatory raw scanner hash/config/count bindings. Full OS+Go scanning found
0 HIGH and 0 CRITICAL vulnerabilities; all lower-severity findings remain in raw.

The actual pulled image's configuration and root filesystem match the retained
OCI config. The gateway SHA-256 is unchanged from the official release:
`a44bb13582e9f62da5cf6f0c24285b7f575c68cab2959e12e547d3841c172bc5`.
The frozen compatibility runner completed 36 checks against this published
server and the published MC digest `d163502c0d23dd3d76ec9fab63d9697317c9a2aaa39d1a3d783a70044bb803c0`.
It used new owner-labelled storage and an internal network, no published ports,
a 768 MiB server cap and a 384 MiB client cap with GOMEMLIMIT=256MiB.
Initialization twice, public/private access, versioning, old/current version
reads, S3 copy restoration and same-volume graceful restart all passed.
All three owned residual resources were removed successfully.

The replacement is for the local test environment only. Official Versity POSIX
versioning remains experimental; these checks establish neither production
durability nor physical backup equivalence. Use a new versity_data volume and
preserve the old MinIO volume. An additional read-only local inventory saw one
bucket and no objects/versions; it does not exclude writes after its timestamp.
No VPS resources, current service, old volume, Compose default or activation
registry was changed by this proof. activation-candidate.json is a validated
proposal for the coordinator, not an applied activation.

Verify the complete byte graph and retained proofs with:

`node --test infrastructure/runtime-security/proofs/c14-versity-published/published.test.mjs`
