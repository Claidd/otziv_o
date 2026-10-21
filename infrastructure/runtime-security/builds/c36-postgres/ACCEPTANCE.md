# PostgreSQL C36 acceptance

C36 starts from the accepted C23 PostgreSQL 17.11 image and rebuilds only
`/usr/local/bin/gosu` from the same gosu source with Go 1.27.3. The scanner's
`postgres-c36-adjudication.mjs` exports the stopped image, checks every file
against the frozen C23 runtime proof, and rejects any change beyond `gosu`.

Publication must include these files beside `publication.json`:

- `vulnerabilities.json`, `vulnerabilities.adjudications.json`, and
  `vulnerabilities.triage.json` from the pinned CI scan;
- `vulnerabilities.runtime-c36.json.gz` copied without modification from the
  scanner scratch file `postgres-runtime-c36.json.gz`;
- `registry-amd64-config.json` and the usual registry and anonymous evidence.

The Compose database reference stays at C23 until an independent C36 acceptance
exists. Run `postgres-c36-rehearsal.py` against a **local, isolated**
`otziv-prod-local` fixture with the active C23 PostgreSQL and C35 Keycloak.
Store `--private-directory` outside this repository; it contains credentials,
dumps, and logs and must never be published. Use an immutable published C36
digest for the replay:

```text
python -B infrastructure/runtime-security/postgres-c36-rehearsal.py capture \
  --private-directory <outside-repository-private-directory> \
  --output infrastructure/runtime-security/proofs/c36-postgres/rehearsal
python -B infrastructure/runtime-security/postgres-c36-rehearsal.py replay \
  --private-directory <same-private-directory> \
  --output infrastructure/runtime-security/proofs/c36-postgres/rehearsal \
  --candidate-postgres ghcr.io/claidd/otziv-security@sha256:<published-digest>
```

After both receipts pass, create
`infrastructure/runtime-security/proofs/c36-postgres/acceptance.json` with
schema `otziv-postgres-c36-acceptance-v1`, `result: PASS`, the exact published
`reference`, `imageConfigId`, C23 `parentReference`/`parentConfigId`, and the
publication `commit`, `run`, and `attempt`. Bind the C36 image and C35 Keycloak
in `pair.postgres` and `pair.keycloak`. Include `runtimePath`, `capturePath`,
`replayPath`, SHA-256 hashes for every publication and rehearsal artifact in
`files`, and SHA-256 hashes of both inspection scripts, the adjudicator, and
the rehearsal script in `executedSources`.

Register the acceptance path and its exact SHA-256 in both
`postgresC36Acceptance` and `databaseTransition` on the PostgreSQL activation
entry. `validatePostgresC36ActivationScan`, `validatePostgresC36Transition`,
and `couplePostgresC36WithKeycloakC35` then replay the scan, file comparison,
same-volume upgrade/rollback, and active issuer checks. Missing or mismatched
proof blocks activation.
