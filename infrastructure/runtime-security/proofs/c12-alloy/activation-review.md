# Independent review of the C12 Alloy activation candidate

Result: **PASS within the reviewed scope; no blocking finding.**

This is a new review of the activation delta after the frozen 73-check source review. It does not replace that review, the 56-check artifact-closure review, or immutable C7 records. No network, Docker, scanner, build, runtime, database or shared-source operation was executed for this review.

The candidate is `.codex-tmp/security-closure-20260908/c12-alloy-integration/candidate`. The comparison baseline is the existing publication worktree at C11. `verification.json` records all input byte hashes, 145 independent offline binding checks and the retained test-log counts.

## Activation code

`reviewed-image-defaults.mjs` SHA256: `3cbc559384badc01856171c43741649c934487fc59646d8e1d4ec2c50f2e2aff`.

The new `validateAlloyReassessment` path accepts only component `alloy`, the exact C7 published reference and image config, and the current bounded review. The loader checks the review's expiry on every activation validation. The reassessment must provide exactly three hash-bound inputs: the raw report, adjudication and effective summary.

The report's image config, full rootfs and complete labels match the retained C7 report. Independently, those rootfs and labels also match the digest-checked actual OCI config. The receipt binds the raw report SHA, current review SHA, executable SHA, canonical build information, pinned parser, module, closure, inspection mode and expiry. Both exact CVE decisions are recomputed from the raw report; the complete effective summary is then recomputed and compared. Additional fixed or vendor-unfixed findings remain ineligible.

The previous publication/anonymous/OCI validation remains in place. An original `NONE` publication cannot acquire an unnecessary reassessment. Only an original `REQUIRED` publication can enter the new path, and that path rejects every other component. `ACCEPTED`, missing or unknown original risk states do not authorize activation. The unchanged downstream default validation and evidence reader still enforce database holds, original mappings, registered references, path containment, raw-part reconstruction and registry digests.

## Exact source and evidence bindings

- Frozen C7 manifest SHA remains `d48bdb7d6d869cde5d6f1a7b089491765e3eeaf46e0b0b6bb51b7719b38d3ef2`, with all 30 original mappings.
- The eight prior activation entries are field-identical. Alloy is the only ninth entry. There is no MySQL or PostgreSQL activation entry, and both database holds remain unchanged.
- Each of the three Compose files differs from the publication baseline only by replacing the single Alloy `sourceBeforeRef` with the reviewed digest.
- Both copied Alloy C7 proof sets are byte-identical to the retained extracted artifacts and their API-digest-verified ZIP entries. Every retained OCI artifact digest was recomputed. The old C7 publication still says `REQUIRED`; historical evidence was not rewritten.
- C7 reference: `ghcr.io/claidd/otziv-security@sha256:a4ce96701d88e336791d1cca7745f5a87060a9834d8f4dd391de2a2072f57852`.
- OCI config: `sha256:7f5b0079e8c6b13cd2a767cdeb001430bdcf0db400afbaf925285d1ae29dd084`.
- Actual fresh raw report SHA256: `f68a6b04986287053ac2dd8f9d4248120f5e3c07b01eeefc725392e70fe0108a`.
- Actual fresh receipt SHA256: `49f63d2b4e99c3dea5989abbce966ddb9ace2bcff7e44d11028d9f40e9b39172`.
- Actual fresh summary SHA256: `638b3afbb727238da69dea652612e9bc7be39b86432c327b43e9262f72fbf3e2`.

The fresh raw report retains two HIGH findings, both vendor-unfixed. Exactly `CVE-2026-41567` and `CVE-2026-42306` on `usr/bin/alloy`, the reviewed Docker module/version/PURL and executable are adjudicated as affected code absent. The independent recomputation yields zero effective fixed and zero effective unfixed HIGH/CRITICAL findings. This is exact-artifact analysis, not risk acceptance or a module-wide exclusion.

## Retained validation and limits

The actual scan execution ended successfully at 07:32:05 UTC; every recorded scanner-source and output hash matches the candidate or retained raw file. The copied activation proof files match those actual outputs exactly.

The actual runtime receipt binds 13 passed checks, exit code zero, four clean process shutdowns and cleanup success. Harness bytes and captured configuration hashes match. It verifies source-to-candidate persistent file positions, candidate restart, stopped-source backup and restore into a separate rollback volume. The source file hash is restored exactly; subsequent appends are read without replaying acknowledged bytes. The retained connection-refused diagnostic comes from readiness polling; it is not the terminal result.

This runtime evidence explicitly covers synthetic local data and the file-cursor pipeline. It does not claim production historical data coverage, a fresh full Docker-observer pipeline test, or production deployment approval. Historical source mappings remain available for the existing rollback procedure.

Retained tests: **17 activation**, **47 prior default/evidence**, **50 Alloy/Grafana unit** checks passed. The two added rootfs/label counterfactuals now refresh the nested report hash and assert the specific metadata rejection, closing the test-quality observation raised during review. These tests were run by the owner; this reviewer read their source/results and independently recomputed the 145 bindings, without repeating real operations.

Previous frozen reviews remain at `c12-source-independent-review` and `c12-alloy-independent-review`, unchanged.
