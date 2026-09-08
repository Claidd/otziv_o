# Exact C14 public metadata secret-scan controls

The pinned Gitleaks engine found 201 public metadata matches in the frozen local Keycloak proof and 285 in the retained and final publication proofs. They are SHA-256 file/class-entry digests, public Maven coordinates, and Debian package file names. The raw security/runtime proofs are unchanged.

Each added exception requires a specific detector, one anchored artifact path, and an exact complete line. The large single-line compilation-provenance JSON instead uses exact detector matches, so another value on the same line remains scanned. The explicit staged-index path form supports the ordinary commit hook; other paths and detectors remain outside these exceptions. There are 19 detector/path groups and 483 distinct selectors, with no general hash, directory or detector exclusion.

Run from the repository root with Python 3.11+ and the already available pinned Docker image:

```sh
python infrastructure/runtime-security/proofs/c14-keycloak-gitleaks/reproduce.py
```

The reproducer reads the exact current selectors and confirms that every approved target exists in its reviewed source artifact. It then runs the real scanner on compact copies of those targets: baseline, LF/CRLF, changed values in every group, unknown values on the same or adjacent lines, other paths, an independent detector, the staged-index path, and Git history. Synthetic negative values are deterministic unbound bytes; their detection is first checked under the original configuration. All reports are redacted. This control does not replace the normal full staged-index scan.

`causal-result.json` binds the executed reproducer and configuration. `full-context-results.json` records the separate full local and published context scans, including the unchanged source proof hashes. The earlier local-only 24-case scratch proof is retained as history; the final tracked reproducer covers the combined configuration.
