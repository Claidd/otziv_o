# Authenticated Maven audit and subsequent corrections

The authenticated audit is now proven. The mandatory security gate remains **failed**, with genuine build/test findings and a documented plugin-graph limitation. This report supersedes the earlier **missing Sonatype result** gap; it does not overwrite historical reports or grant risk acceptance.

The subsequent [C4 result and Kotlin correction](MAVEN_HOSTED_C4_AUDIT_2026-09-08.md) record the actual 46-pair/24-HIGH-or-CRITICAL failed gate, cache reuse, corrected runtime versions, and two separately tested exact compatibility-JAR corrections. C2/C3 observations below remain historical and unchanged.

## Hosted evidence

| Run | Result |
|---|---|
| [C2, head `23b64401`](https://github.com/Claidd/otziv_o/actions/runs/34154946485) | Dependency-Check 13 completed NVD, Assembly and authenticated Sonatype analyzers. Maven failed at the configured CVSS ≥ 7 threshold. Public analyzer data was cached. |
| [C3, head `0b031503`](https://github.com/Claidd/otziv_o/actions/runs/34156195253) | Restored C2's cache; NVD and Sonatype analyzers completed, then the same vulnerability threshold failed. All five npm audits passed. No audit was manually duplicated. |

Each JSON report contains **492 dependency entries**, **33 entries with findings**, and **71 unsuppressed artifact/advisory pairs**: 27 HIGH, 8 CRITICAL, 33 MEDIUM and 3 LOW. There are 44 distinct advisory names; 24 pairs originate from OSS Index and 47 from NVD. The existing exact corrections account for another 35 suppressed pairs. C2 and C3 have identical artifact hash/advisory/source/severity tuples. The JSON does not contain an `analysisExceptions` field; completion is established by the actual analyzer log stages, not an assumed empty field.

The [public evidence manifest](MAVEN_HOSTED_AUDIT_2026-09-08.json) records commit identities, artifact digests, report hashes, safe log excerpts and exact proof paths. Original JSON/HTML artifacts remain unchanged under `.codex-tmp/finalization-20260907/hosted-dependency-audit/{c2,c3}-report/`.

The owner observed Guide **Free** usage at approximately 19:52 UTC: **60.9 of 500 credits**, **609 requests**, displayed usage **12%**. This is cumulative account usage, not measured cost attributed to one run. C3's cache hit does not mean zero cost. No paid subscription or credit purchase was made. Default 24-hour OSS cache expiry, full audit scope, remote-error failures and the CVSS threshold remain unchanged.

## Applied fixes after those reports

The runtime patch uses Jackson BOMs **2.21.6 / 3.1.6** and Jsoup **1.23.2**. These changes have separate application-test and packaged-artifact evidence coordinated by the release owner. A runtime BOM does not govern plugin class loaders, so the following additional dependencies were verified and then applied to the corresponding plugin realms:

| Plugin | Explicit fixed dependencies | Verification |
|---|---|---|
| Spring Boot Maven 4.0.8 | Jackson core/databind 3.1.6 | Actual 36-artifact realm; JSON/XML behavior and symbol linkage |
| Dependency-Check 13.0.0 | Jackson core/databind 2.22.2; Jsoup 1.23.2 | Actual 73-artifact realm; YAML/JSR310 compatibility, JSON/XML behavior and symbol linkage |
| Maven Site 3.22.0 | Jsoup 1.23.2; BeanUtils 1.11.0 | Actual 77-artifact realm; real `site:site` rendering of two Markdown documents, introspection behavior and symbol linkage |

The bounded fixture passed **44 checks**. It reproduces the old BeanUtils enum `declaringClass` exposure and verifies the new default blocks it. It also compares old/new Jackson XML numeric bounds and Jsoup namespace-tree depth while checking ordinary parsing, property access and rendered content. These cases reuse the separately reviewed runtime XML fixtures. The three actual plugin goals passed. Bytecode linkage checks examined 56,760 / 69,119 / 14,317 references to the libraries changed in each realm, found no unresolved symbols, and detected a deliberately removed symbol in each counterfactual. Unchanged optional BeanUtils references in Dependency-Check's validator dependency are outside that Jackson/Jsoup delta.

The applied POM checksum is recorded in the manifest. Comparison against the new runtime-patch JAR is performed separately by the release owner; the old full-suite artifact is not misrepresented as having this POM. No local Sonatype call was made with a fixture token; plugin help goals only establish loaded realms, not a complete audit.

## Exact false-match corrections

Nine additional exact package/version rules correct **22 pairs** in the C2/C3 reports. Existing rules remain unchanged. Each new rule records the observed artifact hash, an individual CVE and primary source URLs in [the XML](maven-false-positives.xml):

- `telegrambots-meta:6.9.7.1`: 14 advisories for separately versioned native Telegram messenger clients. The artifact is the Java Bot API metadata/model SDK; the previous `telegrambots` rule intentionally did not cover this separate artifact.
- `spring-boot-loader-tools:4.0.8`: CVE-2022-31691 concerns [STS/Eclipse and VSCode YAML editors](https://spring.io/security/cve-2022-31691/), not Boot archive-packaging classes.
- `gson:2.9.0`: the [CNA record for CVE-2025-53864](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2025/53xxx/CVE-2025-53864.json) identifies Nimbus JOSE JWT and explicitly distinguishes the independent Gson issue. This correction does not claim that old Gson recursion behavior is fixed.
- `h2:2.4.240`: the [maintainer confirms CVE-2018-14335 was fixed in 1.4.198](https://github.com/h2database/h2database/issues/1294#issuecomment-1227877194). The exact current console source still authorizes `tools.do` through `checkAdmin`.
- HttpClient **5.5.1 / 5.5.2**: [Apache's CVE-2026-40542 record](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/40xxx/CVE-2026-40542.json) limits the affected range to `[5.6, 5.6.1)`. Other HttpClient advisories, including those in the shaded transport, remain visible.
- `spring-security-web:7.0.7`: [CVE-2026-47838](https://spring.io/security/cve-2026-47838/) covers the 5/6 branches; the related 7.0 issue was fixed in 7.0.5. This does not declare the deprecated extractor safe.
- `spring-security-crypto:7.0.7`: [the vendor explicitly identifies 7.0.7 as fixed](https://spring.io/security/cve-2026-47842/). Deprecated encryption APIs still require the documented migration; this is an exact advisory/version correction, not a blanket cryptography exemption.
- `kotlin-reflect:2.2.21`: CVE-2026-53914 affects the [KAPT build-cache implementation](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6). The exact reflection JAR has no KAPT/build-cache classes or `ObjectInputStream` references. Kotlin Gradle/KAPT/compiler artifacts are not exempted.

The actual Dependency-Check 13 XML/XSD parser and suppression engine passed **338 checks**, preserving unknown advisories, new versions and foreign artifacts for both NVD and OSS Index sources. Applied to the unchanged old report, exactly 22 reviewed pairs are removed and **49 remain**. That number is a counterfactual on C2, **not** a current whole-graph scan. No CPE-wide, CWE, regex-version or severity exceptions were added.

## Gate still open

Testcontainers' shaded HttpClient 5.5.1 retains genuine findings; dependency overrides cannot replace embedded bytecode. Site still includes vulnerable Jetty 9.4.58. A freely available compatible fixed 9.4 release has not been established; the vendor's EOL distribution requires separate entitlement, and none is assumed.

Dependency-Check 13 resolves published plugin POMs without the consuming project's plugin dependency overrides. Consequently, fixed actual execution realms can coexist with old declared-plugin findings. Those findings remain in the raw report; they are not classified as foreign products or silently excluded. Other lower-severity runtime findings also remain recorded. The next published head must run the mandatory authenticated audit and retain any failure. No risk owner, acceptance deadline or approval was invented.
