import org.owasp.dependencycheck.xml.suppression.*;
import org.owasp.dependencycheck.dependency.*;
import org.owasp.dependencycheck.dependency.naming.*;
import org.owasp.dependencycheck.analyzer.DependencyBundlingAnalyzer;
import com.github.packageurl.PackageURL;
import com.fasterxml.jackson.databind.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Offline proof against actual ODC 13 classes. No Engine/analyzer network or provider call. */
public class C4KotlinSuppressionProof {
  static int checks;
  static final String CVE = "CVE-2026-53914";
  static final String PREFIX = "pkg:maven/org.jetbrains.kotlin/";
  static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
    checks++;
  }
  static Dependency dependency(String purl, Collection<String> names, Vulnerability.Source source) throws Exception {
    String artifact=purl.substring(purl.lastIndexOf('/')+1,purl.lastIndexOf('@'));
    String version=purl.substring(purl.lastIndexOf('@')+1);
    Dependency d=new Dependency(new File("offline-fixture/repository/org/jetbrains/kotlin/"+artifact+"/"+version+"/"+artifact+"-"+version+".jar"));
    // Distinct deterministic model hashes prevent file IO; byte-exact real JAR proof is separate.
    d.setMd5sum(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(purl.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    d.setSha1sum(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(purl.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    d.addSoftwareIdentifier(new PurlIdentifier(new PackageURL(purl),Confidence.HIGHEST));
    d.addVulnerableSoftwareIdentifier(new CpeIdentifier("jetbrains","kotlin",version,Confidence.HIGHEST));
    for (String name:names) { Vulnerability v=new Vulnerability(name);v.setSource(source);d.addVulnerability(v); }
    return d;
  }
  static void process(List<SuppressionRule> rules, Dependency d) { rules.forEach(r->r.process(d)); }
  static class Bundler extends DependencyBundlingAnalyzer {
    Bundler() { initialize(new org.owasp.dependencycheck.utils.Settings()); }
    Set<Dependency> compare(Dependency a,Dependency b) {
      Set<Dependency> removed=new HashSet<>();evaluateDependencies(a,b,removed);return removed;
    }
  }
  static Dependency jdk(int n) throws Exception {
    return dependency(PREFIX+"kotlin-stdlib-jdk"+n+"@2.2.21",List.of(CVE),Vulnerability.Source.NVD);
  }
  public static void main(String[] args) throws Exception {
    var parser=new SuppressionParser();
    var before=parser.parseSuppressionRules(new File(args[0]));
    var after=parser.parseSuppressionRules(new File(args[1]));
    check(before.size()==19&&after.size()==21,"exactly two new rules");
    var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
    var oldDoc=f.newDocumentBuilder().parse(new File(args[0]));
    var newDoc=f.newDocumentBuilder().parse(new File(args[1]));
    var oldElements=oldDoc.getElementsByTagNameNS("*","suppress");
    var newElements=newDoc.getElementsByTagNameNS("*","suppress");
    for(int i=0;i<19;i++)check(oldElements.item(i).isEqualNode(newElements.item(i)),"existing rule unchanged: "+i);
    for(int i=19;i<21;i++) {
      Element e=(Element)newElements.item(i);
      var selectors=e.getElementsByTagNameNS("*","packageUrl");var cves=e.getElementsByTagNameNS("*","cve");
      check(selectors.getLength()==1&&cves.getLength()==1,"one PURL and CVE per new rule");
      check(!((Element)selectors.item(0)).hasAttribute("regex"),"no PURL wildcard");
      check(selectors.item(0).getTextContent().equals(PREFIX+"kotlin-stdlib-jdk"+(i-12)+"@2.2.21"),"exact reviewed coordinate");
      check(cves.item(0).getTextContent().equals(CVE),"only reviewed advisory");
      for(String key:List.of("cpe","cwe","cvssBelow","cvssV2Below","cvssV3Below","cvssV4Below","vulnerabilityName","filePath"))
        check(e.getElementsByTagNameNS("*",key).getLength()==0,"no broad selector: "+key);
    }
    for(int n:List.of(7,8))for(var source:List.of(Vulnerability.Source.NVD,Vulnerability.Source.OSSINDEX)) {
      String purl=PREFIX+"kotlin-stdlib-jdk"+n+"@2.2.21";
      var old=dependency(purl,List.of(CVE),source);process(before,old);check(old.getVulnerabilitiesCount()==1,"old XML is red for exact artifact");
      var fixed=dependency(purl,List.of(CVE,"CVE-2099-999999"),source);process(after,fixed);
      check(fixed.getVulnerabilitiesCount()==1&&fixed.getVulnerabilities().iterator().next().getName().equals("CVE-2099-999999"),"new unknown advisory survives");
      check(fixed.getSuppressedVulnerabilities().size()==1,"only reviewed pair recorded suppressed");
      for(String other:List.of(purl.replace("2.2.21","2.2.22"),PREFIX+"kotlin-annotation-processing@2.2.21",PREFIX+"kotlin-gradle-plugin@2.2.21",PREFIX+"kotlin-compiler-embeddable@2.2.21","pkg:maven/org.example/foreign-artifact@2.2.21")) {
        var retained=dependency(other,List.of(CVE),source);process(after,retained);check(retained.getVulnerabilitiesCount()==1,"new version/KAPT/compiler/foreign package not exempt: "+other);
      }
    }
    // Actual bundler makes equivalent affected Kotlin jars a single representative row.
    var old7=jdk(7);var old8=jdk(8);process(before,old7);process(before,old8);
    var removed=new Bundler().compare(old7,old8);check(removed.size()==1,"before: actual bundling collapses two affected artifacts");
    Dependency oldRepresentative=removed.contains(old7)?old8:old7;
    check(oldRepresentative.getVulnerabilitiesCount()==1&&oldRepresentative.getRelatedDependencies().size()==1,"before: one active representative plus related jar");
    // A parent-only correction would merely expose jdk8 on the next report.
    var only7=new ArrayList<>(before);only7.add(after.get(19));
    var half7=jdk(7);var half8=jdk(8);process(only7,half7);process(only7,half8);
    check(half7.getVulnerabilitiesCount()==0&&half8.getVulnerabilitiesCount()==1,"jdk7 correction must not implicitly exempt jdk8");
    check(new Bundler().compare(half7,half8).isEmpty(),"different vulnerability sets remain separate in actual bundler");
    var new7=jdk(7);var new8=jdk(8);process(after,new7);process(after,new8);
    var newRemoved=new Bundler().compare(new7,new8);check(newRemoved.size()==1,"after: actual bundling groups two corrected artifacts");
    check(new7.getVulnerabilitiesCount()==0&&new8.getVulnerabilitiesCount()==0,"both exact jars corrected without alias inheritance");
    int all=0,oldRemoved=0,newRemovedCount=0,retained=0,retainedHC=0;
    JsonNode report=new ObjectMapper().readTree(new File(args[2]));
    for(JsonNode item:report.path("dependencies"))for(JsonNode v:item.path("vulnerabilities")) {
      String purl=item.path("packages").get(0).path("id").asText();String name=v.path("name").asText();
      var source=Vulnerability.Source.valueOf(v.path("source").asText());
      var old=dependency(purl,List.of(name),source);process(before,old);if(old.getVulnerabilitiesCount()==0)oldRemoved++;
      var changed=dependency(purl,List.of(name),source);process(after,changed);
      boolean expected=purl.equals(PREFIX+"kotlin-stdlib-jdk7@2.2.21")&&name.equals(CVE);
      check((changed.getVulnerabilitiesCount()==0)==expected,"only exact newly reviewed C4 representative removed: "+purl+" "+name);
      all++;if(expected)newRemovedCount++;else{retained++;if(Set.of("HIGH","CRITICAL").contains(v.path("severity").asText()))retainedHC++;}
    }
    check(all==46&&oldRemoved==0&&newRemovedCount==1&&retained==45&&retainedHC==23,"C4 raw counterfactual exact delta; not a hosted rescan");
    String result="{\"result\":\"PASS\",\"engine\":\"ODC13 actual XSD/parser, SuppressionRule.process, DependencyBundlingAnalyzer.evaluateDependencies\",\"checks\":"+checks+",\"existingRulesUnchanged\":19,\"newRules\":2,\"newExactArtifactAdvisoryPairs\":2,\"C4rawRepresentativePairs\":46,\"C4rawHighCriticalPairs\":24,\"counterfactualRetainedPairs\":45,\"counterfactualRetainedHighCritical\":23,\"bothSourcesTested\":true,\"actualBundlingBeforeParentOnlyAfterTested\":true,\"liveAuditInvoked\":false}";
    Files.writeString(Path.of(args[3]),result+"\n");System.out.println(result);
  }
}
