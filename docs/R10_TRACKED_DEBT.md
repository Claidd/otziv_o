# R10 tracked repository debt baseline

Snapshot date: 2026-08-01.

This report records generated and delivery artifacts that were already tracked before the R10 hygiene gate. The gate deliberately does not fail because of this existing debt. It rejects only files newly added, copied or renamed into protected generated roots.

| Tracked root | Files | Bytes | MiB |
| --- | ---: | ---: | ---: |
| `whatsapp/node_modules/` | 7,607 | 70,854,626 | 67.57 |
| `.codex-tmp/` | 2 | 489,896,217 | 467.20 |
| `.codex-remote-attachments/` | 101 | 7,014,838 | 6.69 |
| `mobile/builds/` | 10 | 82,258,932 | 78.45 |
| `mobile/www/` | 0 | 0 | 0.00 |
| `generated-assets/` | 192 | 315,532,594 | 300.92 |
| **Total recorded debt** | **7,912** | **965,557,207** | **920.83** |

The counts describe the Git index, not untracked local files. `.gitignore` and `.dockerignore` additions do not delete, untrack, move or rewrite any existing file.

Other tracked root-level cleanup candidates requiring an owner decision are `=`, `CHAR(50`, `issue_count`, `payment-profile-comment-preview.png` and `tmp-external-check-181651-3.png`. They are documented but are not automatically blocked or removed because their ownership and operational use are not proven.

Run a current local report without enforcing a diff:

```powershell
./infrastructure/scripts/security/check-repository-hygiene.ps1 -ReportOnly
```

On CI the same script receives the pull-request base or pre-push commit and blocks only new generated artifacts.
