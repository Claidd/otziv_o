# Named reviewed-image publication sets

Publication remains an explicit manual workflow action. `publish-reviewed-images` defaults to false. The separate `reviewed-image-set` choice defaults to `baseline`; selecting another set does not request publication by itself.

| Choice | Fixed manifest | Scope |
|---|---|---|
| `baseline` | `infrastructure/runtime-security/reviewed-images.json` | The unchanged twelve C7 candidates |
| `c12-phpmyadmin` | `infrastructure/runtime-security/reviewed-images-c12-phpmyadmin.json` | Exactly one phpMyAdmin candidate |

The C7 manifest remains byte-for-byte frozen at SHA256 `d48bdb7d6d869cde5d6f1a7b089491765e3eeaf46e0b0b6bb51b7719b38d3ef2`. Its existing publication, anonymous-download evidence, original service mappings and activation entries are preserved.

Inventory, publication and anonymous verification read the same `OTZIV_REVIEWED_IMAGE_SET` environment variable. Unknown values and paths are rejected. Existing positional commands are unchanged; an omitted variable selects `baseline`. The C12 inventory contains only phpMyAdmin and cannot schedule the twelve-image matrix. Selecting a missing C12 manifest fails instead of falling back to C7.

The C12 manifest uses the existing `otziv-reviewed-images-v1` schema with `publicationSet` equal to `c12-phpmyadmin` and `baselineManifestSha256` equal to the frozen C7 hash. It must contain exactly one `phpmyadmin` row. The context is fixed to `infrastructure/runtime-security/builds/phpmyadmin-alpine` and the Dockerfile to its `Dockerfile`. `sourceBeforeRef` and the complete `defaultReferencesBefore` array must equal the original C7 phpMyAdmin row. Platform is `linux/amd64`; host preparation commands are forbidden. Normal manifest/build-argument validation and the actual Dockerfile hash check still apply.

Assemble that manifest only after the Alpine source, exact base references, Dockerfile hash and local compatibility/security evidence have been frozen. The selection support does not manufacture a candidate digest or a publication result.

New C12 publication and anonymous receipts carry the selected manifest name and fixed path. The publication also carries the SHA256 of the exact selected file. Anonymous verification checks that name, path and hash in addition to the existing commit, run, attempt, component, Dockerfile, platform, SLSA/SBOM evidence, fresh anonymous pull and OCI identity checks. Legacy C7 receipts retain their existing shape and are valid only for the baseline selection. Credentials, permissions, security thresholds and registry destination are unchanged.

After actual publication and a successful matching anonymous proof, a future phpMyAdmin activation may add an `entry.manifest` object containing only `path` and `sha256`. Its path must be the fixed C12 manifest path above; the SHA256 must match those exact file bytes. Activation revalidates its scope against C7, then verifies the publication and anonymous pair using the C12 context and manifest hash. No other component can use this field. MySQL and PostgreSQL coordinated-transition holds remain in force. This mechanism does not perform deployment or accept unresolved security findings.
