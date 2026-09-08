# CLI rebuild evidence (C7)

These original records cover the official-source baseline and repaired CLI JAR.
The two builds each discovered 145 tests: 144 passed, one upstream test was skipped.
The 27-command issuer fixture used the earlier server-only image with the repaired
CLI JAR overlaid. Its PASS is limited to that fixture; it is not a claim about a
published image. The final complete-image receipt is recorded separately.

`complete-cli-inventory.json` retains every payload hash and the complete dependency
comparison. The records contain no credentials or private command output. Artifact
hashes are bound by `source-freeze.json`; entries not copied here remain private
local capture files and are not implied to be present in this directory.

The historical `final-context-freeze.json` covers the exact 31 files supplied to
the local image build. The later recipe README is a 32nd file and is actually
included by `.dockerignore`, contrary to an earlier working note. This additional
documentation is not consumed by any Dockerfile COPY or ADD instruction. The
Dockerfile and every consumed source file remain identical; the entire directory
is not claimed to be byte-identical. Hosted publication must use the new Git commit.
