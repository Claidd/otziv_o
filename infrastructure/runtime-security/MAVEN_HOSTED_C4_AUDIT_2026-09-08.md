# C4 hosted dependency audit and exact Kotlin correction

[C4 run 34159670709](https://github.com/Claidd/otziv_o/actions/runs/34159670709), at commit `c990a5bf0988c9d367c70504f6c4f530e956e3ab`, completed with a **failed Maven vulnerability gate**. All five npm audits passed. The failure is the configured CVSS ≥ 7 threshold, not a missing credential, quota error, or failed analyzer. No acceptance or threshold exception is granted.

The token preflight passed. The job restored public analyzer cache `Linux-dependency-check-13-public-v1-34156195253-1`; NVD completed at 20:33:37 UTC and Sonatype at 20:33:44 UTC on 7 September, both reporting zero elapsed seconds. It saved cache `Linux-dependency-check-13-public-v1-34159670709-1` after the vulnerability failure. This proves completion with the configured authenticated audit and reused cache; it is not a new measurement of remote authentication or zero credit usage. The earlier owner-observed Free usage of 60.9/500 credits and 609 requests belongs to C2/C3, not a measured C4 cost. The reviewer did not read the Sonatype account or token, dispatch a new audit, or call the provider.

The downloaded artifact digest was checked against GitHub's SHA-256. Raw JSON/HTML remain unchanged; [the public manifest](MAVEN_HOSTED_C4_AUDIT_2026-09-08.json) includes report hashes, safe log provenance, exact remaining artifact/advisory rows and proof hashes. JSON SHA-256 is `191016b378d821b4da87bc86c1dd9d08d7fb5c871b24882f271432bd6b5ed1b5`.

## Actual result and remaining scope

The report contains **490 dependency entries**, **25 entries with findings**, **46 unsuppressed artifact/advisory pairs**, and **24 distinct advisory names**. Severity counts are **17 HIGH, 7 CRITICAL, 19 MEDIUM and 3 LOW**; sources are 14 OSS Index and 32 NVD pairs. C3 had 71 pairs and 35 HIGH/CRITICAL pairs. Counts represent scanner entries after bundling, not an invented count of exploitable application vulnerabilities.

| Remaining group | Pairs | HIGH/CRITICAL | Disposition |
|---|---:|---:|---|
| Published plugin POMs: BeanUtils, Lang, HttpClient/Core, Jackson, Jsoup and Plexus | 18 | 13 | Separate actual realm and causal tests prove the pinned execution libraries were fixed. Dependency-Check still resolves old plugin declarations without project-level plugin overrides. Preserve this declared-graph discrepancy and the raw findings; no suppression is proposed for it. |
| Site's Jetty HTTP/IO/Server 9.4.58 | 16 | 9 | Genuine unresolved Site dependency. No compatible freely available fixed release or entitlement has been established. |
| Testcontainers' shaded HttpClient 5.5.1 in docker-java-transport-zerodep 3.7.1 | 2 | 1 | Genuine unresolved embedded transport. A dependency override cannot replace shaded bytecode. |
| Kotlin jdk7 2.2.21 representative, with jdk8 related | 1 | 1 | Exact wrong-artifact correction was independently tested and applied after this run; the hosted raw finding remains unchanged. |
| HdrHistogram, LatencyUtils, Logback and OpenTelemetry runtime entries | 9 | 0 | Lower-severity findings retained; no risk acceptance or new exception. |

The current runtime Jackson databind **2.21.6 / 3.1.6** and Jsoup **1.23.2** appear without active findings. The older Jackson/Jsoup versions in the blocking rows are explicitly reported under `otziv_o (plugins)`; they must not be presented as current application runtime versions. [The preceding report](MAVEN_HOSTED_AUDIT_2026-09-08.md) records the actual plugin realm and behavior proof; earlier Plexus/transport proof remains in [the build-tool manifest](MAVEN_BUILD_TOOL_PROOF_2026-09-07.json).

## What happened to the 22 previously reviewed pairs

None of the 22 exact package/advisory pairs reviewed against C2 remains active on its reviewed artifact in C4. The report contains 39 suppressed representative pairs. It is incorrect to add the previous 35 suppressed rows to 22: the [actual bundler](https://github.com/dependency-check/DependencyCheck/blob/v13.0.0/core/src/main/java/org/owasp/dependencycheck/analyzer/DependencyBundlingAnalyzer.java) runs after suppression, groups dependencies with matching findings, and moves other artifacts into `relatedDependencies` without retaining every individual suppression row on the representative. Both corrected Spring artifacts are present with the reviewed hashes as related dependencies and no active finding, but their individual suppression notes are not retained in the JSON. This is not a claim that the hosted log contains 22 individual rule-hit events. The separate original 338-check engine proof and exact C4 identity mapping remain available.

## Subsequently applied Kotlin jdk7/jdk8 correction

The [JetBrains CNA record](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/53xxx/CVE-2026-53914.json) concerns build-cache metadata deserialization. The [vendor fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6) changes the KAPT incremental-cache implementation and its tests. In contrast, each exact runtime compatibility JAR below contains only `META-INF/versions/9/module-info.class`, alongside directory/manifest entries. Neither has executable KAPT code or an `ObjectInputStream` reference.

| Exact artifact | Size | SHA-256 |
|---|---:|---|
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.21` | 945 bytes | `b785922f11e6d91a6dd1d75cb0aef1ce37b83f8de0e3a2139139dfb823bb8a2c` |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.21` | 951 bytes | `c62275c50ee591ca2f82c7ba42696b791600c25844f47e84bd9460302a0d5238` |

These byte-identical artifacts already existed as related dependencies of `kotlin-reflect` in C3. After the exact reflection correction, jdk7 became the affected C4 representative and jdk8 remained related. This is a changed reporting representative, not a newly introduced dependency.

After review, **two separate literal package URLs at version 2.2.21**, each with only **CVE-2026-53914**, were added to [the XML](maven-false-positives.xml). Application first asserted the C4 XML hash; the POM remains unchanged. The tested patch is retained as `.codex-tmp/finalization-20260907/hosted-dependency-audit/c4-report/proposed-suppressions.patch`; its complete XML and guarded application hashes are recorded in the manifest. No existing rule is widened. Kotlin compiler, Gradle and annotation-processing/KAPT artifacts remain outside the correction.

The actual Dependency-Check 13 XSD/parser, `SuppressionRule.process`, and `DependencyBundlingAnalyzer.evaluateDependencies` passed **129 offline checks**. [The complete proof source](proofs/C4KotlinSuppressionProof.java) takes four arguments: the C4 XML, corrected XML, unchanged C4 JSON report, and output JSON path. Compile/run it with the recorded ODC 13 plugin realm and Maven 3.9.15 library classpath; no Maven audit goal is involved. The test proves the original XML leaves both artifacts affected; a jdk7-only rule merely exposes jdk8 separately; two exact rules correct both. All 19 existing rules remain structurally identical. Unknown advisories, a new version, foreign artifacts and the actual compiler/Gradle/KAPT coordinates remain unsuppressed for NVD and OSS Index inputs. The test uses in-memory dependency models and the real engine classes; exact artifact content and hashes were verified separately. No complete audit or provider request was invoked.

Applied to the unchanged C4 report as a **counterfactual**, the proposal removes its one grouped Kotlin representative: **45 pairs and 23 HIGH/CRITICAL pairs remain**. Two underlying artifact rules do not mean two reported rows are removed. This is not a hosted rescan or a passing security gate. Publication and the next hosted result are controlled separately by the release owner.
