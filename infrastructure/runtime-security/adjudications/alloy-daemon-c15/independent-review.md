# C15 Alloy: automated verification with an independent parser

The published executable was reproduced byte for byte. The retained Go parser and the independently implemented Python ELF parser agree on all 418,393 functions, with 474 Docker client functions as a positive control and no Docker/Moby daemon functions. Both source-graph verifiers traverse the complete collector entrypoint closure: 5,199 packages and 53,420 resolved import edges. Source closure covers inlining, which function names alone cannot exclude.

All 56 checks passed on these C15 inputs. This is an automated second-parser verification; no second human or agent review is claimed. The verifier is retained with its exact inputs and can be rerun offline with source-fixture, repository, publication-artifact and output directories. It never executes the inspected binary.

Only CVE-2026-41567 and CVE-2026-42306, the exact usr/bin/alloy target, image, build information, module version and PURL qualify. The retained primary advisories scope these findings to absent daemon packages. No other CVE, executable, host Docker Engine or future image is exempted. Raw scanner findings remain retained. The C7 proof remains unchanged and cannot authorize this image.
