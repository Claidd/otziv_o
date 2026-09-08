# Published PostgreSQL 17.11 candidate verification

Exact published reference: `ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf`. Configuration digest: `sha256:d257683e1d6febdfb869b77c60683bc598b272788d60e65893a109adaf0a2db4`.

Publication job 102106707216 and anonymous-download job 102112100425 succeeded in [run 34239698507](https://github.com/Claidd/otziv_o/actions/runs/34239698507), attempt 1, commit `e3d798c423eaa7ee1afab9f77c041286e408fefa`. Only those two jobs are claimed here; unrelated workflow completion is not inferred.

The two original downloaded ZIPs matched GitHub API digests and are retained along with byte-identical extracted files. Existing `validateActivation` accepts the versioned c14-postgres manifest and replays the complete publication/anonymous OCI descriptor, source provenance and configuration chain. The 11 build-context files also match their frozen reviewed hashes and exact Git blobs at the publication commit. Calling this validator is a read-only verification; publication-candidate.json has not been registered or activated.

The actual pulled image matches the published OCI configuration and rootfs. A new stopped-container export passes the frozen PostgreSQL inspector: all 43 review files, all 1,358 executable/library/script/configuration payload records, complete path inventory, actual source/package revisions and GNU patch controls match the previously reviewed independent local build. This is runtime/source payload reproduction across the local and hosted builds; bit-identical OCI images or another new upstream build are not claimed. The inspector executes no image binary and removes its own stopped container/export.

The raw hosted scan remains unchanged: 152 total findings, 26 HIGH and 1 CRITICAL. The 37 exact existing classifications, including 17 fixed and 10 unfixed HIGH/CRITICAL rows, are reproduced against the actual published runtime. Effective HIGH/CRITICAL counts are 0/0 only after this verification. No finding was removed, no blanket suppression added, and no residual risk was accepted. Review expiry remains 2027-01-01. The existing publication validator currently does not itself replay the PostgreSQL raw scan/adjudication; the separate frozen inspector and explicit count/hash binding in this proof supply that check. The causal adjudication suite passes 12/12 again.

New execution against the actual published digest passed 21/21 continuity and 5/5 TCP checks. The unchanged frozen runners use synthetic data only, owner-labelled fresh volumes, one CPU and 512 MiB per database, a 128 MiB TCP client, no published ports, and either no network or an internal bridge. They verify 17.10→17.11 same-volume upgrade, 41 Unicode controls / 820 equality comparisons, permissions/XML, new writes/restart, native gzip initialization, separate fresh 17.10 logical rollback before and after upgrade, authenticated TCP, wrong-password rejection and committed data across restart. All owned resources were cleaned up. The previous frozen local-image and actual-VPS failure proofs were not overwritten.

No current stack, existing database volume, VPS, default, activation registry or database hold was changed. Actual VPS Keycloak migration remains a separate blocked prerequisite recorded in `../c14-postgres-vps/`; these synthetic PostgreSQL checks do not establish readiness to activate it.

Read-only verification:

`node --test infrastructure/runtime-security/proofs/c14-postgres-published/published.test.mjs`
