# Independent Alloy exact-artifact review

**PASS for the proposed two-CVE non-affected finding, restricted to the exact artifact below.** This is code-absence evidence, not acceptance of a vulnerable daemon or a general Docker-module exemption. No shared scanner rules were changed by this review.

| Identity | Verified value |
| --- | --- |
| C7 image index | `sha256:a4ce96701d88e336791d1cca7745f5a87060a9834d8f4dd391de2a2072f57852` |
| Raw scan | `0ed6934afd2fce73be9eff9ecbfd2ee69c92ab4f210b06e88ca71315dbc8beec` |
| Raw target/type | `usr/bin/alloy`, `gobinary` |
| Actual target binary SHA256 | `2c21e2c85e1e88c88b3335d39c84ae5414232514aeaaea02457fef54f4c5e3dc` |
| Exact PURL | `pkg:golang/github.com/docker/docker@v28.5.2%2Bincompatible` |
| Reviewed IDs only | `CVE-2026-41567`, `CVE-2026-42306` |

The independent verifier hashes the actual 536,798,920-byte published binary and directly decodes the ELF Go function table using Python rather than the original Go `debug/gosym` parser. All 418,393 names match the retained parser output. It finds 474 Docker-module functions as a positive control and no daemon functions.

Function names alone would not exclude compiler inlining. The stronger evidence is the matching source closure: all 5,199 packages are reachable from the actual collector main through 53,420 resolved import edges; no incomplete packages, missing imports or disconnected padding were accepted. Neither the legacy Docker daemon nor the Moby daemon package variants occur. The same exact main, Linux/amd64, CGO setting and four build tags are used by the preserved vendor Makefile. Eight before/after module files and canonical Go build information match the publication. A supported SOURCE_DATE_EPOCH restores the observed publication timestamp; the retained successful build log and output hash prove complete binary equality after the earlier failed equality attempt. Both attempts remain retained.

Primary scope supports the specific exclusion:

- The retained [Go advisory GO-2026-5746](https://vuln.go.dev/ID/GO-2026-5746.json) maps CVE-2026-41567 to the daemon package and `Daemon.containerExtractToDir`. Those packages are absent from the complete source closure.
- The retained [Moby advisory GHSA-rg2x-37c3-w2rh](https://github.com/moby/moby/security/advisories/GHSA-rg2x-37c3-w2rh) maps CVE-2026-42306 to Docker Engine and the daemon package variants. Those are absent as well.
- Alloy's immutable `.govulncheck.yaml` corroborates these two IDs and the outbound-client distinction; it was not treated as sufficient evidence on its own. The previously fetch-denied GO-2026-5617 URL was not retried.

An initial evidence gap was reported: the original copied path was `/bin/alloy`, while the scan names `usr/bin/alloy`. Root supplied a separate stopped-container copy of **`/usr/bin/alloy`**. The independent verifier hashes that actual file, verifies equality to the published binary, matches image rootfs/labels, and checks retained owner/cleanup identity. The gap is closed; there is no need to allow additional target paths.

The original analysis script has no blocking analytical defect. Its high package/function count assertions alone would not prove completeness, but the explicit main-`Deps` equality and resolved-import validation do; this review additionally traverses all edges from main and independently reads the binary table. Source absence covers inlining better than a symbol-only or string-search claim. Changing SOURCE_DATE_EPOCH changes metadata only through the vendor-supported path and was followed by full SHA equality, not a normalized-binary comparison.

Integration must conjunctively bind the reviewed component, exact image/config provenance, raw report identity, target/type, package/version/PURL and the two named CVEs. Preserve both raw findings and mark their specific non-affected disposition; count other findings normally. A changed binary, image, target, package or advisory needs new evidence. Do not use this finding to exempt the host Docker Engine, a remote daemon contacted by Alloy, other image executables, future CVEs, or other entries in the upstream ignore list. The independent result does not establish that every aspect of the image or deployment is secure.

`verification.json` records 56 checks and every actual input hash. `verify.py` is offline and uses only Python standard-library file parsing. This review executed no network calls, Docker commands, scanners, builds, inspected binaries, or shared-source mutations. Gate implementation and its counterfactual tests remain root-owned.
