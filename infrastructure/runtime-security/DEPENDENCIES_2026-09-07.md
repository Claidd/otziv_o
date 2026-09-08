# Java transport dependency correction

The first real scan of the packaged application found seven HIGH and three CRITICAL package findings with available fixes. These are scanner ratings; exploit preconditions differ by feature. In particular, Tomcat's own advisory rates the FORM and DIGEST issues Low and describes authenticator-specific preconditions. We apply the supported fixes without claiming the application's Spring Security authorization was bypassed and without adding exclusions.

| Family | Before (Boot 4.0.7) | Selected | Management |
|---|---|---|---|
| Netty | 4.2.15.Final | 4.2.16.Final | `netty.version` changes the imported family BOM |
| Tomcat embed | 11.0.22 | 11.0.25 | `tomcat.version` aligns core, EL and WebSocket |
| HttpCore 5 | 5.3.6 | 5.4.3 | `httpcore5.version` aligns core and HTTP/2 |
| HttpClient 5 | 5.5.2 | 5.5.2 | Retain the existing Boot-managed version |

Primary evidence checked on 2026-09-07:

- [Spring Boot 4.0.7 dependency POM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.7/spring-boot-dependencies-4.0.7.pom) supplies the original versions and property hooks.
- [Netty 4.2.16.Final release](https://github.com/netty/netty/releases/tag/netty-4.2.16.Final) and its [published BOM](https://repo.maven.apache.org/maven2/io/netty/netty-bom/4.2.16.Final/netty-bom-4.2.16.Final.pom) establish the released patch artifact.
- [Tomcat 11 security advisories](https://tomcat.apache.org/security-11.html#Fixed_in_Apache_Tomcat_11.0.25) identify the fixes in 11.0.25, including CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525.
- [Apache HttpComponents release notes](https://hc.apache.org/news.html) explicitly describe HttpClient **5.5.2** as fixing compatibility with HttpCore **5.4**. Therefore the Core fix does not require an unrelated Client minor upgrade. The [official download page](https://hc.apache.org/download.html) identifies 5.4.3 as the stable Core release; beta/alpha metadata entries were not selected.

Scoped Maven dependency convergence checks reject diverging versions within these transport families. Verify actual resolution and the packaged runtime inventory after changing any BOM or override:

```sh
cd backend
./mvnw -B -ntp dependency:tree '-Dincludes=io.netty:*,org.apache.tomcat.embed:*,org.apache.httpcomponents.core5:*,org.apache.httpcomponents.client5:*'
./mvnw -B -ntp verify
cd ..
node infrastructure/runtime-security/scan.mjs java backend/target /secure/evidence/maven-vulnerabilities.json
```

The first scan read 346 recognized Java components from 334 nested runtime JARs. The initial image JAR and the full-verify JAR had identical SHA-256 values for every nested runtime JAR. After the override change, Maven convergence and actual packaged-family versions passed; the exact-JAR rescan recognized 346 components and found 0 HIGH/CRITICAL. The targeted test runs included the crawler's real HTTP fixture tests with Core 5.4.3. See [recorded hashes and evidence](EVIDENCE_2026-09-07.md).

Preserve the before/after reports and SBOM. Review these temporary properties on the next Spring Boot upgrade; remove an override only when the parent manages an equal or newer compatible security release and the convergence, tests and exact-artifact scan pass. No CVE ignore list or downgraded scanner threshold accompanies this change.
