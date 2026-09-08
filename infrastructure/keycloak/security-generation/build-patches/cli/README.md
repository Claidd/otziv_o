# Keycloak CLI dependency rebuild

The official Keycloak 26.7.3 distribution places both `kcadm` and `kcreg` entrypoints in `bin/client/keycloak-admin-cli-26.7.3.jar`. Its shaded Jackson dependencies require their own update; replacing the server's separate library JARs does not change this CLI.

The Docker stage builds the official `integration/client-cli/admin-cli` module from Keycloak commit `6d238b6558037085cc25c915893c3d301a80243e` (tag `26.7.3`). The source archive SHA-256 is `74675b6df593843c5f9384c145c04515c293db228ce76afa9d70c61040f69602`. The supplied module POM preserves the upstream build, shading filters and descriptor generation and adds only a Jackson BOM `2.21.6` dependency-management import. The five resolved Jackson modules move from `2.21.5` to `2.21.6`; annotations remain `2.21`. The launchers and all Keycloak Java source stay unchanged.

The pinned Maven/Temurin 17 builder matches the upstream CLI compiler generation. A baseline rebuild reproduced all 3,964 original JAR file payloads exactly, including all 466 Keycloak classes. The repaired build preserved every non-Jackson payload except the embedded module POM that records the dependency change. ZIP timestamps may change the outer JAR digest. The complete dependency inventory contains 25 JARs; Maven verifies repository checksums, and `cli-dependencies.sha256` additionally binds every resolved compile, runtime, provided and test JAR to its reviewed SHA-256. The build rejects a different dependency count or checksum.

The original and repaired source builds each discover 145 upstream tests: 144 pass and the existing `org.keycloak.client.registration.cli.ReflectionUtilTest.testListAttributes` remains skipped. The build does not disable tests. `CliJacksonProof.java` checks the actual shaded versions of all five Jackson modules and runs two 64-digit constraint controls plus an ordinary duration control. The original CLI fails the seven relevant checks; the repaired CLI passes all eight checks. The final image runs the same proof against its installed CLI with networking disabled and proof classes mounted only for that build step. No compiler or verifier classes are copied into the runtime image.

The output is a downstream dependency rebuild, not an unchanged publisher JAR. Keep its source recipe, dependency checksums, complete artifact comparison and actual-image validation together when reviewing a release.

Official source: https://github.com/keycloak/keycloak/tree/6d238b6558037085cc25c915893c3d301a80243e/integration/client-cli/admin-cli
