## C14 mc candidate

`mc.Dockerfile` rebuilds archived AGPL mc commit
`77f82e18b5401a65958f1619df6ebb994634bd88` with the pinned Go 1.27.1 image.
`mc.go.mod` and `mc.go.sum` are the reviewed complete dependency graph: builds
download checksum-verified modules and compile/test with `-mod=readonly`.
The dependency diff and corresponding source archive are included in the image
under `/usr/share/otziv-build/mc/`; the binary retains its real upstream revision,
dirty source marker, and an explicit `otziv-c14.1` derivative release suffix.

The pinned UBI9 base is updated through its normal signed package repositories.
`runtime-packages.lock` compares the complete resolved NEVRA inventory before
removal. Future repository drift fails the build and requires a newly reviewed
lock. Availability of these exact repository packages is an external dependency;
this is a fail-closed repeatable recipe, not a promise that a vendor will retain
its repositories forever. The runtime then removes curl, libcurl, microdnf,
libdnf, librepo, rpm, rpm-libs, libmodulemd and libsolv in one ordinary dependency
checked transaction. These tools are unnecessary for `mc`. The real updated RPM
database and OS identity remain present for scanning; no findings are ignored.
`/bin/sh`, CA trust and the actual mc binary remain available.

Run the bounded runtime proof with a local immutable image ID and a new output
directory:

```text
node infrastructure/runtime-security/builds/minio/run-mc-proof.mjs sha256:<local-image-id> <new-output-directory>
```

It enforces local Docker, creates an internal network and synthetic owned volume,
publishes no ports, uses fresh in-memory credentials and removes only its own
resources. It exercises the production initialization command twice, anonymous
public GET, denied anonymous private GET, authenticated bytes, version IDs and
old/latest version reads, and object copy restoration. This mc proof does not
claim that server data upgrades or physical production backups were rehearsed.

The retained C14 proof is in `../../proofs/c14-minio-mc/`. Publication must rebuild
the committed recipe, scan the actual resulting image again, and retain registry
provenance/SBOM and the immutable registry digest. Local evidence alone does not
activate an image in production. The upstream project is archived; this narrow
rebuild does not claim ongoing vendor support or fix MinIO server vulnerabilities.
