import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.utils.Settings;
import org.owasp.dependencycheck.analyzer.AbstractSuppressionAnalyzer;
import org.owasp.dependencycheck.analyzer.VulnerabilitySuppressionAnalyzer;
import org.owasp.dependencycheck.xml.suppression.*;
import org.owasp.dependencycheck.dependency.*;
import org.owasp.dependencycheck.dependency.naming.*;
import com.github.packageurl.PackageURL;
import com.fasterxml.jackson.databind.*;

/** Direct offline seam into unmodified ODC 13, with complete preserved C13 reports. */
public final class JTidySuppressionProof {
    static final String SHA1 = "e57994fdeb7077b11a8ba05c93e3cb89c3ad8ed0";
    static final String SHA256 = "7cea4360710adb44c588e13afdcdc0171fc1a3e61cc4562703ddb1f0a36f0ca4";
    static final String PURL = "pkg:maven/com.github.jtidy/jtidy@1.0.5";
    static final String CVE = "CVE-2023-34623";
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static String hash(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }
    static class ActualAnalyzer extends VulnerabilitySuppressionAnalyzer {
        void apply(Dependency dependency, Engine engine, List<SuppressionRule> rules) throws Exception {
            engine.putObject(AbstractSuppressionAnalyzer.SUPPRESSION_OBJECT_KEY, rules);
            // Injecting the real parser result prevents any remote suppression rule loading.
            prepareAnalyzer(engine);
            analyzeDependency(dependency, engine);
        }
    }
    static Dependency dependency(Path file, String purl, String sha1, String sha256,
                                 Collection<String> cves, Vulnerability.Source source) throws Exception {
        Dependency d = new Dependency(file.toFile());
        d.setSha1sum(sha1);
        d.setSha256sum(sha256);
        if (!purl.isBlank()) d.addSoftwareIdentifier(new PurlIdentifier(new PackageURL(purl), Confidence.HIGHEST));
        for (String name : cves) {
            Vulnerability v = new Vulnerability(name);
            v.setSource(source);
            d.addVulnerability(v);
        }
        return d;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("before.xml after.xml exact.jar old-r938.jar raw-report-directory result.json");
        Path beforeXml = Path.of(args[0]), afterXml = Path.of(args[1]);
        Path jar = Path.of(args[2]), oldJar = Path.of(args[3]), reports = Path.of(args[4]), output = Path.of(args[5]);
        byte[] bytes = Files.readAllBytes(jar), oldBytes = Files.readAllBytes(oldJar);
        Path engineJar = Path.of(Engine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var archive = new JarFile(engineJar.toFile())) {
            var properties = new Properties();
            try (var in = archive.getInputStream(archive.getJarEntry("META-INF/maven/org.owasp/dependency-check-core/pom.properties"))) {
                properties.load(in);
            }
            check(properties.getProperty("version").equals("13.0.0"), "actual engine version is exactly ODC13.0.0");
        }
        check(hash(bytes, "SHA-1").equals(SHA1) && hash(bytes, "SHA-256").equals(SHA256), "actual hosted archive SHA1 and SHA256");
        check(hash(oldBytes, "SHA-256").equals("6fc03e51e73fa884f06e7eae0761e045e56fdeb4e146a4d952e3023cc9e3fb43"), "actual old r938 archive");
        var parser = new SuppressionParser();
        var before = parser.parseSuppressionRules(beforeXml.toFile());
        var after = parser.parseSuppressionRules(afterXml.toFile());
        check(after.size() == before.size() + 1, "exactly one new rule");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var oldNodes = factory.newDocumentBuilder().parse(beforeXml.toFile()).getElementsByTagNameNS("*", "suppress");
        var newNodes = factory.newDocumentBuilder().parse(afterXml.toFile()).getElementsByTagNameNS("*", "suppress");
        for (int i = 0; i < oldNodes.getLength(); i++) check(oldNodes.item(i).isEqualNode(newNodes.item(i)), "prior rule unchanged");
        Element newRule = (Element) newNodes.item(oldNodes.getLength());
        check(newRule.getAttribute("until").equals("2027-01-01Z"), "fixed UTC expiry");
        check(newRule.getElementsByTagNameNS("*", "sha1").getLength() == 1
                && newRule.getElementsByTagNameNS("*", "sha1").item(0).getTextContent().equals(SHA1), "literal exact archive selector");
        check(newRule.getElementsByTagNameNS("*", "cve").getLength() == 1
                && newRule.getElementsByTagNameNS("*", "cve").item(0).getTextContent().equals(CVE), "one advisory only");
        for (String broad : List.of("packageUrl", "filePath", "cpe", "cwe", "cvssBelow", "cvssV2Below", "cvssV3Below", "cvssV4Below", "vulnerabilityName")) {
            check(newRule.getElementsByTagNameNS("*", broad).getLength() == 0, "no alternative/broad selector");
        }
        Path expiredXml = output.resolveSibling("expired-selector.xml");
        Files.writeString(expiredXml, Files.readString(afterXml).replace("until=\"2027-01-01Z\"", "until=\"2000-01-01Z\""));
        var expired = parser.parseSuppressionRules(expiredXml.toFile());
        byte[] changed = Arrays.copyOf(bytes, bytes.length + 1);
        changed[changed.length - 1] = 10;
        Path changedJar = output.resolveSibling("changed-byte-fixture.jar");
        Files.write(changedJar, changed);
        var settings = new Settings();
        var analyzer = new ActualAnalyzer();
        List<Map<String, Object>> reportResults = new ArrayList<>();
        int occurrences = 0, removed = 0, highCriticalRemoved = 0;
        try (Engine engine = new Engine(Engine.Mode.EVIDENCE_PROCESSING, settings)) {
            analyzer.initialize(settings);
            for (var source : List.of(Vulnerability.Source.NVD, Vulnerability.Source.OSSINDEX)) {
                var original = dependency(jar, PURL, SHA1, SHA256, List.of(CVE), source);
                analyzer.apply(original, engine, before);
                check(original.getVulnerabilitiesCount() == 1, "old XML stays red");
                var exact = dependency(jar, PURL, SHA1, SHA256, List.of(CVE, "CVE-2099-999999"), source);
                analyzer.apply(exact, engine, after);
                check(exact.getVulnerabilitiesCount() == 1 && exact.getVulnerabilities().iterator().next().getName().equals("CVE-2099-999999"), "new advisory retained");
                check(exact.getSuppressedVulnerabilities().size() == 1, "adjudicated finding remains recorded");
                var modified = dependency(changedJar, PURL, hash(changed, "SHA-1"), hash(changed, "SHA-256"), List.of(CVE), source);
                analyzer.apply(modified, engine, after);
                check(modified.getVulnerabilitiesCount() == 1, "changed actual archive remains red despite identical PURL");
                var old = dependency(oldJar, "pkg:maven/net.sf.jtidy/jtidy@r938", hash(oldBytes, "SHA-1"), hash(oldBytes, "SHA-256"), List.of(CVE), source);
                analyzer.apply(old, engine, after);
                check(old.getVulnerabilitiesCount() == 1, "vulnerable r938 retained");
                var future = dependency(changedJar, "pkg:maven/com.github.jtidy/jtidy@1.0.6", hash(changed, "SHA-1"), hash(changed, "SHA-256"), List.of(CVE), source);
                analyzer.apply(future, engine, after);
                check(future.getVulnerabilitiesCount() == 1, "other version retained");
                var automaticHash = dependency(jar, PURL, null, null, List.of(CVE), source);
                analyzer.apply(automaticHash, engine, after);
                check(automaticHash.getVulnerabilitiesCount() == 0 && SHA1.equals(automaticHash.getSha1sum()), "real analyzer computes artifact hash from bytes");
                var missing = dependency(output.resolveSibling("absent.jar"), PURL, null, null, List.of(CVE), source);
                analyzer.apply(missing, engine, after);
                check(missing.getVulnerabilitiesCount() == 1, "missing artifact/hash retained");
                var noLongerValid = dependency(jar, PURL, SHA1, SHA256, List.of(CVE), source);
                analyzer.apply(noLongerValid, engine, expired);
                check(noLongerValid.getVulnerabilitiesCount() == 1, "expired rule does not suppress");
            }
            try (var files = Files.list(reports)) {
                for (Path report : files.filter(p -> p.toString().endsWith(".json.gz")).sorted().toList()) {
                    JsonNode document;
                    try (InputStream in = new GZIPInputStream(Files.newInputStream(report))) { document = new ObjectMapper().readTree(in); }
                    int count = 0, corrections = 0, highBefore = 0, highAfter = 0;
                    for (JsonNode d : document.path("dependencies")) for (JsonNode v : d.path("vulnerabilities")) {
                        String sha1 = d.path("sha1").asText(), sha256 = d.path("sha256").asText(), cve = v.path("name").asText();
                        String purl = d.path("packages").path(0).path("id").asText();
                        var source = Vulnerability.Source.valueOf(v.path("source").asText());
                        // Report hash evidence is retained verbatim; only the exact target needs local archive content.
                        var dep = dependency(sha1.equals(SHA1) ? jar : output.resolveSibling("report-only.jar"), purl, sha1, sha256, List.of(cve), source);
                        analyzer.apply(dep, engine, before);
                        int beforeCount = dep.getVulnerabilitiesCount();
                        analyzer.apply(dep, engine, after);
                        boolean expected = sha1.equals(SHA1) && sha256.equals(SHA256) && cve.equals(CVE);
                        check(beforeCount - dep.getVulnerabilitiesCount() == (expected ? 1 : 0), "complete C13 report delta is only exact reviewed pair");
                        boolean high = Set.of("HIGH", "CRITICAL").contains(v.path("severity").asText());
                        if (high && beforeCount > 0) highBefore++;
                        if (high && dep.getVulnerabilitiesCount() > 0) highAfter++;
                        count++;
                        if (expected) {
                            corrections++;
                            check(dep.getSuppressedVulnerabilities().size() == 1, "raw occurrence retained as suppressed evidence");
                            if (high) highCriticalRemoved++;
                        }
                    }
                    occurrences += count;
                    removed += corrections;
                    reportResults.add(Map.of("report", report.getFileName().toString(), "rawOccurrences", count, "correctedOccurrences", corrections, "highCriticalBefore", highBefore, "highCriticalAfter", highAfter));
                }
            }
        }
        check(reportResults.size() == 6 && removed == 2 && highCriticalRemoved == 2, "all six C13 reports, precisely two duplicate HIGH occurrences corrected");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "PASS"); result.put("checks", checks);
        result.put("engine", "Unmodified ODC13 SuppressionParser/XSD + VulnerabilitySuppressionAnalyzer");
        result.put("engineJar", engineJar.getFileName().toString());
        result.put("engineJarSha256", hash(Files.readAllBytes(engineJar), "SHA-256"));
        result.put("beforeXmlSha256", hash(Files.readAllBytes(beforeXml), "SHA-256"));
        result.put("afterXmlSha256", hash(Files.readAllBytes(afterXml), "SHA-256"));
        result.put("artifactSha256", SHA256); result.put("rawOccurrences", occurrences);
        result.put("correctedOccurrences", removed); result.put("reports", reportResults);
        result.put("sourcesTested", List.of("NVD", "OSSINDEX"));
        result.put("negativeCases", List.of("other CVE", "altered actual JAR same PURL", "old r938", "other version", "missing artifact/hash", "expired rule"));
        result.put("providerCalls", 0); result.put("fullAudit", false);
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Files.writeString(output, json + "\n");
        System.out.println("PASS actual ODC13 selector: " + checks + " checks; corrected " + removed + " of " + occurrences + " C13 report occurrences");
    }
}
