# C12 phpMyAdmin archive-digest scan correction

After the previous metadata correction, the normal 115-file commit scan reported
one remaining `generic-api-key` match in line 5 of
`infrastructure/runtime-security/builds/phpmyadmin-alpine/COMPONENT_COMPARISON_2026-09-08.json`.
The matched value is the retained C7 publication ZIP digest. Actual ZIP bytes
match the captured GitHub artifact digest and the unchanged comparison evidence.

One additional AND-scoped block permits only this detector, exact artifact path
and complete reviewed line, including LF/CRLF handling. The field, raw artifact,
prior configuration and earlier proofs remain unchanged.

Pinned Gitleaks reproduced baseline one finding and candidate zero. Seven cases
passed, including CRLF and controls for another path, changed digest, another
field, and an adjacent deterministic unusable credential. The adjacent credential
was caught by the independent hardcoded-literal detector. No real credentials
were used; reports/logs are redacted.

The compact proof is
`infrastructure/runtime-security/proofs/c12-phpmyadmin/secret-metadata-review.json`.
This local proof does not assert future hosted-history completion. Normal hooks,
detectors and release gates remain mandatory.
