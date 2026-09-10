# Client contracts and pure helpers

`@otziv/client-common` **1.1.0** exposes generated request/response DTOs, operation metadata and a transport-neutral SDK for the routes selected in `client-api.config.json`: manager, common billing, public/admin payments, manager control/reports and the manual-payment cabinet. The current export has **188 operations, 173 paths and 205 input/output schemas**. The Java controllers, Jackson DTOs, enums and validation annotations own the wire contract; the configuration selects scope and records the explicitly retired public status endpoint's HTTP 404. It does not duplicate field definitions.

`ClientApiContractModel` reads the compiled production Spring MVC mappings and Jackson 3 converter. The export includes query/path/header parameters, request bodies, success status, pagination, permission fields, `PreAuthorize` metadata and the API error schema. It separates input from output because validation and omission rules differ. `ClientApiJacksonFixtureTest` round-trips the DTO closure through that converter. The fixtures are synthetic, never customer data. `ClientApiMvcContractTest` exercises real controllers and advice with isolated application ports for parameters, JSON, validation before dispatch, 204, 409 and the retired 404 route. Its standalone MockMvc setup does not itself execute Spring method-security proxies; those require the backend authorization tests.

## Reproduce and check

Use the repository's required JDK and run from `backend` after a wire-contract change:

```sh
./mvnw -B -ntp -Dtest=ClientApiContractExportTest,ClientApiJacksonFixtureTest,ClientApiMvcContractTest -Dclient.contract.export=true test
```

Then from the repository root:

```sh
node contracts/generate.mjs
node contracts/generate.mjs --check
npm ci --prefix frontend
npm ci --prefix mobile
node --test contracts/client-common.test.mjs
node contracts/verify-release-compatibility.mjs
npm run test:runtime --prefix mobile
npm run test:unit --prefix mobile
npm test --prefix frontend -- --watch=false
npm run build:prod --prefix mobile
npm run build --prefix frontend
```

Without `client.contract.export=true`, the Java tests compare the checked-in artifacts and do not rewrite them. Node generation verifies normalized source hashes first, then deterministically derives TypeScript from the compiled contract. The existing small order-editor/billing generators remain compatibility projections for their established adapters; their Java serialization tests remain enabled. Cross-platform source hashes normalize CRLF to LF. A source change requires re-export and review, even when the resulting shape is unchanged.

Both apps install the versioned local package through committed `.npmrc` (`install-links=true`), and Docker copies the same package before `npm ci`. Regenerate before installing; a changed shared source does not update a previously installed package copy automatically. No private registry is required for this package. The regular third-party dependencies still use the configured npm registry.

## Runtime transport and compatibility

The generated `ClientApiOperations` map provides typed path, query, body and response types for every selected operation. `prepareClientOperation` creates a request description; it never sends a request. Each app's `clientApiContractInterceptor` validates matching JSON requests/responses while retaining Angular `HttpClient`, existing auth/error/telemetry interceptors, cancellation and error objects. It lazy-loads the schema catalog. It does not retry, cancel or replay writes. The narrow feature APIs remain the transport owners, and legacy `ApiService` methods delegate during migration.

Runtime HTTP tests use real `HttpClient` and `HttpTestingController`: pagination/parameters, permissions, invalid input before dispatch, status mutation once/204, unknown timeout with no replay, preserved 409, GET cancellation and binary bypass. Node parity tests decode the same Java fixtures with both installed copies. Numeric IDs must be safe JavaScript integers, kopeck amounts stay integer values, and malformed permission fields fail validation. Unconstrained Java `Object` fields remain unknown; the exporter does not invent their structure or infer business authorization from a DTO.

Unknown output enum strings are preserved for display compatibility. Payment adapters separately disable payment capabilities for unknown states/modes/routes; typed requests retain known enum constraints. Existing optional compatibility fields remain optional only where the established configuration declares them. Missing payment capability never grants an action. Shared helpers do not calculate authoritative payment amounts or recipient attribution.

See [ROUTING_BEHAVIOR.md](ROUTING_BEHAVIOR.md) for the executable routing parity matrix, including the intentional legacy empty-route difference. Invoice delivery warnings use `lastError`, independently of legacy invoice status, and preserve the operation identifier without a second send.

## Released clients

[releases/README.md](releases/README.md) describes the actual latest published Android 73 artifact, verified package/signature/hash, observed running backend identity and offline Jackson fixtures from that exact published JAR. The unchanged APK web assets have also run against candidate DTOs in a loopback browser with all external traffic denied. `verify-release-compatibility.mjs` checks retained operations/authorization metadata and bidirectional published/candidate schemas plus the observed old-client payment request. This is stronger than a filename-based release claim, but is not proof of every native screen or signed upgrade.

The observed release service advertises `minSupportedVersionCode: 0`; that does not identify a tested oldest artifact. `minimumSupported` remains null and `--require-supported-releases` intentionally fails until the release owner defines and tests that minimum. Keep existing endpoints/fields through the support window, deploy additive server changes before clients, and do not silently raise a production minimum to make a gate pass. Native upgrade, PKCE/refresh/push and distribution rollback require their own release evidence.
