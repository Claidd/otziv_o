# Historical partial candidate; activation prohibited

This candidate updated the server and boot JARs but retained vulnerable Jackson
2.21.5 classes inside the administrative CLI. Its successful functional tests
and zero Trivy HIGH/CRITICAL findings do not establish advisory closure.

`vulnerabilities.json` retains the exact scanner bytes and provides a regression
input for the independent known-dependency gate. `reviewed-images.json` records
the manifest at this local iteration; it was not published. `validation.json`
records the observed platform index, manifest and config identities, actual
checks and remaining finding. Its relative evidence paths refer to the original
local task capture under `.codex-tmp/security-closure-20260908/issuer-remediation`.

Later candidates must have separate image identities and validation records.
