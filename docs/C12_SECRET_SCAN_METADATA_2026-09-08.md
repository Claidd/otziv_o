# C12 secret-scan metadata correction

The normal pre-commit scan stopped on 218 matches in preserved public image
evidence: 157 Go module/checksum lines, 60 SPDX detector findings arising from
190 empty-content verification fields (95 per paired artifact), and one
scanner-deduplicated upstream commit reference present in seven identical URL
origins. Detector counts and source occurrence counts are distinct. These are public metadata,
not credentials. No raw evidence bytes are changed.

The candidate adds seven AND-scoped blocks covering eight exact artifact paths.
Five Go evidence files require the complete reviewed line, including module,
version and checksum, with LF/CRLF handling. The two minified SPDX files permit
only their exact empty-content verification value. The formatted scanner report
requires its complete reviewed public URL line. No detector, severity policy,
decoding setting, history gate or existing allowance is changed.

The 32 Go module/version responses were obtained from official sum.golang.org
over HTTPS. Their response hashes and exact module/version/h1 lines were checked;
this is not a claim of independent Ed25519 tree-note signature verification.
The review binds the public lookup URLs, response hashes and original artifact
hashes without reproducing matched values in a new report path.

Pinned Gitleaks 8.30.1 reproduced all 218 baseline findings. The candidate produced
zero findings for the full fixture, absolute staged-copy-equivalent input and
Go CRLF variant. Thirty-two negative controls and six additional paired baseline
controls passed: changed checksums/versions/paths/JSON values, adjacent synthetic
credentials, percent-encoded credentials and unrelated local detectors still
trigger. Together with the baseline and three positive scans, 42 accepted engine
cases passed. Test credentials are deterministic, publicly reproducible unusable
bytes. Causal logs/reports are redacted.

Initial controls with an extended version string or a context-free URL line did
not exercise the original detectors correctly; a long Windows fixture path also
failed before scanning. These receipts remain retained. Corrected controls use
one changed version digit, the real scanner keyword context and short private
paths. The candidate configuration was unchanged during these corrections.

The JSON review is at
`infrastructure/runtime-security/proofs/c12-alloy/secret-metadata-review.json`.
It records the exact scope and evidence hashes. The configuration is included in
the input and selected by `--config`; the vendor's existing self-config exclusion
is unchanged. This proof covers directory/staged-copy behavior, not future hosted
Git-history completion. Normal hooks and hosted checks remain mandatory.
