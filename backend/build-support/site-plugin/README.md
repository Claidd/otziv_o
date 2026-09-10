<!--
Copyright 2026 Otziv contributors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
https://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
# Maven Site with Jetty 12 EE8

This is an Otziv downstream derivative of Apache Maven Site Plugin 3.22.0, **not an Apache release**. The separate version `3.22.0-otziv-jetty12.0.39-2` never overwrites the vendor GAV. Keeping the original group/artifact is deliberate: Maven's ordinary `site` lifecycle resolves that identity. All ten goal descriptors and their parameters are unchanged. Java 17 or newer and Maven 3.9.15 or newer are required; the application build uses Java 26 and Maven 3.9.15.

The source archive is public at https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-site-plugin/3.22.0/maven-site-plugin-3.22.0-source-release.zip. Its exact digest and each vendored file are checked by `proofs/SiteSourceProvenance.java` and `UPSTREAM-SOURCES.tsv`; `upstream.patch` records every delta. LICENSE and NOTICE are preserved. The module contains all 21 main Java files and all 26 runtime resources. It includes all seven upstream unit-test source files and their three resources; the upstream multi-project integration-test development tree is not vendored and its suite is not claimed as executed.

## Build and use

Artifact deployment is skipped, and inherited distribution destinations are overridden with local file locations. The bootstrap invokes installation only.

Run `mvn -B -ntp -f backend/build-support/site-plugin/pom.xml clean install` before the backend build. No private Maven repository or paid Jetty entitlement is required. The backend's existing Site plugin declaration selects the downstream version; its Jsoup 1.23.2 and BeanUtils 1.11.0 overrides remain. Both `mvn site` and `mvn site:run` retain their normal meanings. The derivative also declares those two fixed libraries so its standalone realm has the same protections.

To verify source provenance after downloading the public archive, run `java backend/build-support/site-plugin/proofs/SiteSourceProvenance.java <archive.zip> backend/build-support/site-plugin`. A changed archive, changed vendored file, duplicate manifest entry or extra unmanifested source is rejected. The accompanying `SiteGoalProof.java` invokes the real Maven run goal using Maven's own libraries; its only HTTP targets are its fresh loopback fixture.

## Deliberate changes

Jetty is 12.0.39, using the EE8 servlet/webapp artifacts to retain `javax.servlet`. Two main classes have three API migrations: the EE8 WebAppContext import, its core handler adapter, and MimeTypes.DEFAULTS. The remaining 19 main sources and all runtime resources match the release archive byte for byte. Preview HTTP compliance is left at Jetty's strict defaults. The bootstrap compile/test graph uses Maven 3.9.15, Mockito 5.20.0 and Guava 33.6.0-jre. Maven Resolver api/util are aligned to the Maven 3.9.15 baseline, 1.9.27; this avoids mixing the upstream reporting executor's old 1.4.1 API with the current Maven implementation. The separately declared transport-wagon API remains at its actual 1.4.1 version; no ineffective management override is claimed as an upgrade.

The two test helpers use Jetty EE8 APIs. Only their fake WebDAV receivers permit `AMBIGUOUS_EMPTY_SEGMENT`, because the unchanged Wagon 3.5.3 test request spells a destination `/site/.//path`. This reproduces the original test server's contract; it does not change preview or application HTTP policy. Upload contents and authenticated/unauthenticated proxy checks remain asserted.

## Evidence and limits

The private closure evidence records all four upstream unit tests passing with no skips, ordinary static rendering, both live-document rendering and updates, resources/404 behavior, and graceful Jetty shutdown. The same malformed chunk-extension request causes two handled requests on upstream Jetty 9.4.58; 12.0.39 closes the connection before handling the second request. Doxia can commit the first 200 response before body framing is parsed; this test deliberately asserts second-request rejection and EOF, not a fabricated first-response 400.

The initial actual 80-artifact execution realm was scanned in full with Trivy 0.74.0: zero findings. The later bootstrap dependency alignment and its changed Resolver artifacts have separate evidence; the initial scan is not relabeled as that final graph. Its scan, metadata-only packaging delta and final source/JAR identities are recorded separately. This bounded result does not substitute for the required authenticated whole-project Dependency-Check gate. No advisory suppression, threshold reduction, disabled analyzer, feature removal or Jetty commercial repository is introduced.


The downstream build has no Apache Maven parent. Its effective dependency versions,
Java 17 compilation, generated help/descriptor goals, JAR manifest and Apache
LICENSE/NOTICE resource bundle are explicit. Apache-specific release publication,
source formatting, RAT, SCM publication and reporting automation are not part of
this locally installed derivative build. Their former inherited activation is
removed; vulnerability analyzers still inspect every active build/report/extension
plugin and every project scope. The ordinary clean build retains all executable
classes, resources and plugin descriptors byte-for-byte; only the embedded POM changes.

The final Git sources use LF throughout the origin-checked files. Five existing
CRLF files (the POM and four patched Java sources) were normalized without other
text changes, and their downstream hashes and archive patch were regenerated.
The original fresh checkout failed the byte-level provenance check; corrected
checkouts pass with both Git newline policies. Reapplying the patch to the exact
archive reproduces all 60 files. An offline Site-only rebuild passes all four
tests and preserves every runtime and test class byte; only line endings of the
embedded POM differ from the previously scanned JAR. Historical scan identities
remain recorded separately in the C6 remediation evidence.

The `-2` derivative pins Zstd JNI 1.5.7-14 in the project and the Maven plugin realms that use it. This addresses [GHSA-jfr6-9xqw-2g2q](https://github.com/luben/zstd-jni/security/advisories/GHSA-jfr6-9xqw-2g2q) and [GHSA-ff36-7w3w-g8rm](https://github.com/luben/zstd-jni/security/advisories/GHSA-ff36-7w3w-g8rm). The earlier scan remains historical; the new dependency graph requires its own audit.
