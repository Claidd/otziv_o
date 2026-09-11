# Ordinary report uploads

This composite reuses the pinned official `actions/upload-artifact` v7.0.1.
It retries **prepared report files**, not tests, builds or image publication.
There are at most three upload actions with two-second/five-second delays.
Names are `${name}-run-${github.run_attempt}-upload-{1,2,3}` and `overwrite`
is always false. Archive mode is explicitly true: the official non-archive mode
ignores the requested artifact name and would defeat attempt naming. Different
caller reports must still have distinct base names.

Use only for ordinary CI reports. Reviewed image publication and anonymous-pull
evidence retain their original names, actions, run/attempt binding and acceptance
chain. This composite does not fetch evidence from an earlier run.

Each official action's actual `outcome`, not its continue-on-error `conclusion`,
controls retries. Only internal upload attempts continue on error. The final
step fails unless one upload succeeded, or the official action succeeded with
empty outputs under the caller's existing `warn`/`ignore` no-files policy.
The default is the official `warn` policy; required reports should keep `error`.
Partial output, cancellation, missing preparation and all failed attempts fail.
The accepted artifact ID, URL, digest and exact name come from that successful
attempt. Consumers must use these outputs or discover the suffixed names.

An ambiguous FinalizeArtifact failure can leave an artifact behind. Subsequent
attempts create a different immutable name; neither deletion nor overwrite is
performed. Failed attempt logs and their outcome summary are retained in the
same job. This is bounded resilience against report transport failures, not a
diagnosis or repair of the external HTTP403 cause, and not an RPC-level retry.

Internal conditions explicitly include `!cancelled()` so a caller with
`if: always()` can upload diagnostics after an earlier test/scan failure.
They do not clear the original job failure. The final step runs with `always()`
and rejects cancellation. A successful retry cannot make a failed scan pass.

Local causal checks: `node --test .github/actions/upload-report/receipt.test.mjs`.
They execute the final CLI and test success/failure/no-files/cancellation and
the actual action wiring. GitHub service behavior still requires the final
hosted run; local tests do not claim a successful remote retry.
