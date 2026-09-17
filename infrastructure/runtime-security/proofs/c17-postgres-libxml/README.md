# September 2026 libxml2 source review

The published C16 PostgreSQL image already contains libxml2 2.15.4. Debian's
tracker lists CVE-2026-86138, 86139, 86142, 86143 and 86144 as fixed in 2.15.4;
the scanner compares our locally built package against Debian stable instead.

`review.json` binds each archived Debian advisory, upstream fix patch and
release source file. All five source files were compared byte for byte with
the SHA256-pinned 2.15.4 release tarball already bound into the C16 runtime.
The supplemental validator verifies the full stopped-image runtime using the
unchanged C16 validator before accepting exactly these five package findings.
Different runtime bytes, package versions, scanner sources, unknown findings,
expired evidence or altered receipts fail closed. Raw scanner output is retained.

`scanner-report.json.gz` contains the unchanged report from GitHub Actions run
35218882160, artifact 10496377536 (2026-09-17), for the published C16 image.
Existing publication and activation evidence is not rewritten.
