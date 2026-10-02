# R10 recovery artifacts: historical baseline and completed migration

Historical snapshot date: 2026-08-03 (post-audit index).

At that snapshot the index deliberately retained the two sets of delivery
artifacts below until an independent recovery copy could be verified. These
figures describe the historical baseline, not the current checkout.

| Tracked root | Files | Bytes | MiB |
| --- | ---: | ---: | ---: |
| `mobile/builds/` | 10 | 82,258,932 | 78.45 |
| `generated-assets/` | 192 | 315,545,434 | 300.93 |
| **Total retained recovery artifacts** | **202** | **397,804,366** | **379.38** |

On 2026-10-02 the complete 202-file Git snapshot (397,805,360 bytes; the source
importer had changed since August) was archived to the dedicated private backup
S3. A version-bound authenticated download and an isolated restore on the VPS,
using its pre-existing backup key, verified every file hash. Nine APK signatures,
package identities and versions were verified separately against the same bytes.
The records and file manifest are in `infrastructure/artifact-recovery/`.

The migration untracks 199 binary artifacts (397,760,618 bytes), preserving
local ignored copies and the three tracked importer/source manifests. The
hygiene gate now rejects any tracked binary in these roots and checks the
offline manifest/receipt bindings. Deletions in the migration diff are accepted
only when their old Git blob exactly matches the verified archive entry.
Android verifier tests generate their own throwaway signed APK fixtures;
neither historical APKs nor production backup/signing credentials are required.
See [recovery instructions](RETAINED_ARTIFACT_RECOVERY.md).

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

On CI the script receives the pull-request base or pre-push commit, enforces the
current tree and validates any historical-artifact deletion against the reviewed
recovery manifest. This does not rewrite Git history or remove old blobs.
