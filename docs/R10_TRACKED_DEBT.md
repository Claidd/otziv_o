# R10 tracked repository recovery-artifact baseline

Snapshot date: 2026-08-03 (post-audit index).

The current index deliberately retains the two sets of non-reproducible delivery
artifacts below. A clean clone therefore still contains the signed Android
releases and the generated notification media needed for emergency recovery.
The hygiene gate reports this existing debt without deleting it and rejects new
files added to these protected roots. It also rejects modification or deletion
of the retained binary artifacts until that recovery migration is reviewed.

| Tracked root | Files | Bytes | MiB |
| --- | ---: | ---: | ---: |
| `mobile/builds/` | 10 | 82,258,932 | 78.45 |
| `generated-assets/` | 192 | 315,545,434 | 300.93 |
| **Total retained recovery artifacts** | **202** | **397,804,366** | **379.38** |

The counts describe the Git index, not ignored local files. Do not untrack either
retained root until a signed release/object-storage copy has been verified from
a second machine and the production deploy no longer reads the checkout copy.

The remediation removes only reproducible dependencies (`whatsapp/node_modules`),
Codex temporary/attachment material, database-query fragments (`=`, `CHAR(50`,
`issue_count`) and two diagnostic screenshots from the current index. Those
paths have no runtime/deploy references; lockfiles remain tracked so dependencies
can be recreated. Existing Git history is unchanged until a separate, explicitly
approved history-cleanup operation is performed.

Run a current local report without enforcing a diff:

```powershell
./infrastructure/scripts/security/check-repository-hygiene.ps1 -ReportOnly
```

On CI the same script receives the pull-request base or pre-push commit and blocks only new generated artifacts.
