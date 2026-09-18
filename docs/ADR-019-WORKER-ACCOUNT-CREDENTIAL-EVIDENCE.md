# ADR-019: Account credential evidence before a worker block

## Decision

`business_audit` owns durable `CREDENTIAL_REVEAL` records and exports only
`CredentialRevealEvidence.hasBothCredentials`. Its caller is the worker activity
account guard. The projection returns a boolean for an explicit actor, card,
account and time interval; it exposes neither credentials nor audit rows. Reads
use a REQUIRED read-only transaction, joining an existing mutation transaction
when present. Missing scope fails closed.

`worker_activity` exports `WorkerAccountBlockGuard` to review account commands,
review bot changes, recovery tasks and bad-review tasks. The task owner remains
responsible for authentication, role/assignment authorization and transaction
ownership. Commands pass their captured actor; existing synchronous services use
the current authenticated actor. The guard enforces credential evidence for plain
workers, preserving existing manager/admin/owner behavior. Rejection occurs before
account mutation, cooldown consumption and activity recording; the guard writes
nothing and starts no independent mutation transaction.

The guard and risk evaluator share the same evidence lookup. Both credential
reveals must follow the latest account-change event on that card. They do not
expire after fifteen minutes. Evaluation excludes its own activity by ID because
database timestamp precision can otherwise turn the current block into a new
assignment boundary. Evidence proves credential retrieval, not an external login
or the external platform's suspension decision.

## Verification

Architecture checks enforce owner boundaries without new persistence, internal
access or cycle exceptions. MySQL regression tests cover the eighteen-minute
incident, missing password, actor/card/account scope and assignment reset.
Service tests ensure rejected blocks neither mutate accounts nor consume cooldown.
