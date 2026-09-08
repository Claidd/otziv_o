# Released-client fixtures

`android-73` is tied to the publicly advertised latest APK, not inferred from its filename:

- `published-update.json`: public `/api/mobile-update` observation; advertised SHA matches the local archived APK.
- `provenance.json`: actual package/version, verified certificate, observed running backend image identity/revision and the exact JAR hash.
- `backend.openapi.json` and `backend-fixtures.json`: generated **offline from that published JAR's classes and its own Spring/Jackson libraries**, using the current exporter and archived source at the image revision. The export format version is not a claim about an SDK deployed in that historical APK.
- `request-public-init.json`: request actually emitted by the unchanged APK73 web assets in the loopback browser check.
- `candidate-public-payment-response.json`: synthetic current backend DTO response used in that browser check; this is not a response copied from a real customer's payment.

`node contracts/verify-release-compatibility.mjs` verifies published DTO JSON against the candidate SDK, candidate JSON against published schemas, the observed old-client write, retained operations/authentication boundaries and unchanged known enums. It does not run provider calls. A wire-compatible response is not proof of every screen, native plugin or signed upgrade.

`node contracts/released-client-smoke.mjs --assets <unchanged extracted assets/public> --apk-sha256 <verified hash> --report <new report path>` runs the actual archived web client with deterministic local responses. Verify the APK with `aapt dump badging`, `apksigner verify --print-certs` and SHA-256 before extracting; preserve asset bytes. The runner denies all external traffic and never contacts a bank or backend. Keep its asset-hash manifest with the APK verification evidence. Backend artifact export can be reproduced with `ClientApiContractArtifactRunner`, placing verified `BOOT-INF/classes` and `BOOT-INF/lib/*` ahead of test dependencies on the Java classpath and passing an isolated archived source root.

The observed production metadata declares `minSupportedVersionCode: 0`. This neither identifies an existing minimum APK nor proves all historical clients. Therefore `minimumSupported` remains `null`; **`--require-supported-releases` fails intentionally**. Establish the supported minimum and matching archived artifact/backend fixtures before enabling that release gate. Do not change a production minimum merely to make a check green.

`local-artifacts.json` additionally records actual manifest/signature/hash verification of the local debug APK53 and release APK54–73. All 21 artifacts use the same verified certificate. Unchanged web assets from all 20 release APKs passed the public payment fixture flow (60 checks). Version54 is only the oldest available artifact tested in that limited flow; this does not set a product support minimum or establish that each local file was published.

`released-client-smoke.mjs` also accepts `--fixture <JSON>` and `--client-kind candidate-build` in place of `--apk-sha256` for current bundle probes. `ArtifactPublicPaymentFixture.java` round-trips a synthetic response with the exact published JAR's own DTO/Spring/Jackson runtime; this is serialization evidence, not a running old backend.

`recovery-candidate-20260907.json` identifies the exact local recovery image/JAR and actual Spring startup/public HTTP evidence on a disposable clone of the expanded schema. `node contracts/recovery-runtime-smoke.mjs --assets <current production bundle> --container <owned recovery container> --image <exact image ID> --report <new report>` verifies its internal network/ownership/image and relays only three synthetic GET paths from the running server. Current web and mobile passed six checks with zero mutations. The fixture preparation and containment evidence are retained in the audit directory, and the findings/limits are documented in `docs/CLIENT_RELEASE_COMPATIBILITY_2026-09-07.md`.

The production `fc191ac` JAR **cannot be used as a binary-only rollback after V298**: actual startup failed because the deliberately renamed `lead_sync_queue` is absent. Do not restore that name, add an alias, or disable schema validation to run an incompatible consumer. The selected local candidate is not a published recovery release; a real paired restore and operational approval remain separate.

The matrix does **not** prove a signed native upgrade, PKCE/refresh/push, every historical screen, or which backend image ran on the original APK publication date. The historical upgrade attempt was blocked before execution by automatic approval review; no device installation occurred. Existing endpoints/fields remain until a measured support window permits removal. Unknown new enums require old-client runtime evidence before publication.
