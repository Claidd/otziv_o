import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.io.File;
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

/** Offline unit seam into the unmodified ODC13 parser and vulnerability suppression analyzer. */
public class C7MavenSuppressionProof {
 static final String PURL="pkg:maven/org.apache.jackrabbit/jackrabbit-webdav@2.14.4";
 static final String SHA1="8b44cca2d95f7c8ec54126a400d77464194ad481";
 static final String SHA256="1d876e7419e223dd90e921b3c122d0164b33c0e4f060b07aa761fcb0eb7bb5c8";
 static final List<String> CVES=List.of("CVE-2023-37895","CVE-2025-58782");
 static int checks;
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
 static String hash(byte[] b,String algorithm)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(b));}
 static class ActualAnalyzer extends VulnerabilitySuppressionAnalyzer {
  void apply(Dependency d,Engine engine,List<SuppressionRule> rules)throws Exception {
   engine.putObject(AbstractSuppressionAnalyzer.SUPPRESSION_OBJECT_KEY,rules);
   // prepare sees the already-loaded parser result and therefore does not load any remote rules.
   prepareAnalyzer(engine);
   analyzeDependency(d,engine);
  }
 }
 static Dependency dependency(Path path,String purl,String sha1,String sha256,Collection<String> cves,Vulnerability.Source source)throws Exception {
  Dependency d=new Dependency(path.toFile());d.setSha1sum(sha1);d.setSha256sum(sha256);
  d.addSoftwareIdentifier(new PurlIdentifier(new PackageURL(purl),Confidence.HIGHEST));
  for(String name:cves){Vulnerability v=new Vulnerability(name);v.setSource(source);d.addVulnerability(v);}return d;
 }
 public static void main(String[] args)throws Exception {
  Path oldXml=Path.of(args[0]),newXml=Path.of(args[1]),jar=Path.of(args[2]),reports=Path.of(args[3]),output=Path.of(args[4]);
  byte[] bytes=Files.readAllBytes(jar);check(hash(bytes,"SHA-1").equals(SHA1),"actual archive SHA1");check(hash(bytes,"SHA-256").equals(SHA256),"actual archive SHA256");
  var parser=new SuppressionParser();var before=parser.parseSuppressionRules(oldXml.toFile());var after=parser.parseSuppressionRules(newXml.toFile());check(after.size()==before.size()+2,"exactly two new rules");
  var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);var a=f.newDocumentBuilder().parse(oldXml.toFile()).getElementsByTagNameNS("*","suppress");var b=f.newDocumentBuilder().parse(newXml.toFile()).getElementsByTagNameNS("*","suppress");
  for(int i=0;i<a.getLength();i++)check(a.item(i).isEqualNode(b.item(i)),"existing rule unchanged");
  Element rule=(Element)b.item(a.getLength());
  check(rule.getElementsByTagNameNS("*","sha1").getLength()==1&&rule.getElementsByTagNameNS("*","sha1").item(0).getTextContent().equals(SHA1),"exact hash selector");
  var selectors=rule.getElementsByTagNameNS("*","packageUrl");check(selectors.getLength()==0,"one supported artifact selector is exact SHA1; no misleading PURL conjunction");
  check(rule.getElementsByTagNameNS("*","cve").getLength()==2,"two named CVEs only");
  for(String broad:List.of("filePath","cpe","cwe","cvssBelow","cvssV2Below","cvssV3Below","cvssV4Below","vulnerabilityName"))check(rule.getElementsByTagNameNS("*",broad).getLength()==0,"no broad selector");
  var analyzer=new ActualAnalyzer();var settings=new Settings();
  byte[] changed=Arrays.copyOf(bytes,bytes.length+1);changed[changed.length-1]=10;Path changedJar=output.resolveSibling("changed-byte-fixture.jar");Files.write(changedJar,changed);
  try(Engine engine=new Engine(Engine.Mode.EVIDENCE_PROCESSING,settings)) {
   analyzer.initialize(settings);
   for(var source:List.of(Vulnerability.Source.NVD,Vulnerability.Source.OSSINDEX))for(String cve:CVES) {
    var old=dependency(jar,PURL,SHA1,SHA256,List.of(cve),source);analyzer.apply(old,engine,before);check(old.getVulnerabilitiesCount()==1,"old XML stays red");
    var exact=dependency(jar,PURL,SHA1,SHA256,List.of(cve,"CVE-2099-999999"),source);analyzer.apply(exact,engine,after);check(exact.getVulnerabilitiesCount()==1&&exact.getVulnerabilities().iterator().next().getName().equals("CVE-2099-999999"),"only named exact pair removed");check(exact.getSuppressedVulnerabilities().size()==1,"suppression audit retains pair");
    JsonNode negative=new ObjectMapper().readTree(new File(args[5]));
    for(JsonNode item:negative) {
     Path otherJar=Path.of(item.path("file").asText());byte[] otherBytes=Files.readAllBytes(otherJar);
     check(hash(otherBytes,"SHA-1").equals(item.path("sha1").asText())&&hash(otherBytes,"SHA-256").equals(item.path("sha256").asText()),"negative artifact exact bytes");
     var other=dependency(otherJar,item.path("purl").asText(),hash(otherBytes,"SHA-1"),hash(otherBytes,"SHA-256"),List.of(cve),source);analyzer.apply(other,engine,after);check(other.getVulnerabilitiesCount()==1,"other actual artifact or version retained");
    }
    var changedDep=dependency(changedJar,PURL,hash(changed,"SHA-1"),hash(changed,"SHA-256"),List.of(cve),source);analyzer.apply(changedDep,engine,after);check(changedDep.getVulnerabilitiesCount()==1,"changed archive bytes retained despite same PURL");
    var calculated=dependency(jar,PURL,null,SHA256,List.of(cve),source);analyzer.apply(calculated,engine,after);check(calculated.getVulnerabilitiesCount()==0&&SHA1.equals(calculated.getSha1sum()),"actual engine lazily verifies exact archive hash");
    Path absent=output.resolveSibling("missing-hash-fixture.jar");check(!Files.exists(absent),"missing file fixture is absent");
    var missing=dependency(absent,PURL,null,null,List.of(cve),source);analyzer.apply(missing,engine,after);check(missing.getVulnerabilitiesCount()==1,"unavailable required SHA1 retained");
   }
   final String kcSha1="009f7fb99f26ba5b43d4a72d7b53f9504c2f1bee",kcSha256="245127ecae84d509522ec01aefdc7b13aaa577ff645498bac43debc981ef0630",kcPurl="pkg:maven/org.keycloak/keycloak-services@26.7.3",kcCve="CVE-2026-18215";
   Path kcJar=Path.of(args[6]);byte[] kcBytes=Files.readAllBytes(kcJar);check(hash(kcBytes,"SHA-1").equals(kcSha1)&&hash(kcBytes,"SHA-256").equals(kcSha256),"actual Keycloak archive SHA1/SHA256");
   Element kcRule=(Element)b.item(a.getLength()+1);check(kcRule.getElementsByTagNameNS("*","sha1").getLength()==1&&kcRule.getElementsByTagNameNS("*","sha1").item(0).getTextContent().equals(kcSha1),"exact Keycloak SHA1 selector");check(kcRule.getElementsByTagNameNS("*","packageUrl").getLength()==0&&kcRule.getElementsByTagNameNS("*","cve").getLength()==1&&kcRule.getElementsByTagNameNS("*","cve").item(0).getTextContent().equals(kcCve),"one Keycloak advisory only");
   for(String broad:List.of("filePath","cpe","cwe","cvssBelow","cvssV2Below","cvssV3Below","cvssV4Below","vulnerabilityName"))check(kcRule.getElementsByTagNameNS("*",broad).getLength()==0,"no broad Keycloak selector");
   byte[] changedKc=Arrays.copyOf(kcBytes,kcBytes.length+1);changedKc[changedKc.length-1]=10;Path changedKcJar=output.resolveSibling("changed-keycloak-byte-fixture.jar");Files.write(changedKcJar,changedKc);
   for(var source:List.of(Vulnerability.Source.NVD,Vulnerability.Source.OSSINDEX)) {
    var kcOld=dependency(kcJar,kcPurl,kcSha1,kcSha256,List.of(kcCve),source);analyzer.apply(kcOld,engine,before);check(kcOld.getVulnerabilitiesCount()==1,"old XML remains red for Keycloak");
    var exact=dependency(kcJar,kcPurl,kcSha1,kcSha256,List.of(kcCve,"CVE-2099-999999"),source);analyzer.apply(exact,engine,after);check(exact.getVulnerabilitiesCount()==1&&exact.getVulnerabilities().iterator().next().getName().equals("CVE-2099-999999")&&exact.getSuppressedVulnerabilities().size()==1,"only exact Keycloak named pair corrected");
    for(JsonNode item:new ObjectMapper().readTree(new File(args[7]))) {
     Path other=Path.of(item.path("file").asText());byte[] otherBytes=Files.readAllBytes(other);check(hash(otherBytes,"SHA-1").equals(item.path("sha1").asText())&&hash(otherBytes,"SHA-256").equals(item.path("sha256").asText()),"negative Keycloak archive identity");
     var d=dependency(other,item.path("purl").asText(),hash(otherBytes,"SHA-1"),hash(otherBytes,"SHA-256"),List.of(kcCve),source);analyzer.apply(d,engine,after);check(d.getVulnerabilitiesCount()==1,"other Keycloak component/version retained");
    }
    var modified=dependency(changedKcJar,kcPurl,hash(changedKc,"SHA-1"),hash(changedKc,"SHA-256"),List.of(kcCve),source);analyzer.apply(modified,engine,after);check(modified.getVulnerabilitiesCount()==1,"changed Keycloak bytes retained");
    var missing=dependency(output.resolveSibling("absent-keycloak.jar"),kcPurl,null,null,List.of(kcCve),source);analyzer.apply(missing,engine,after);check(missing.getVulnerabilitiesCount()==1,"unavailable Keycloak hash retained");
   }
   int pairs=0,removed=0,highCriticalRemoved=0;Set<String> unique=new HashSet<>(),remaining=new HashSet<>();
   try(var paths=Files.walk(reports)) {
    for(Path report:paths.filter(x->x.getFileName().toString().equals("dependency-check-report.json")).toList()) {
     JsonNode doc=new ObjectMapper().readTree(report.toFile());
     for(JsonNode d:doc.path("dependencies"))for(JsonNode v:d.path("vulnerabilities")) {
      String purl=d.path("packages").get(0).path("id").asText(),cve=v.path("name").asText(),sha=d.path("sha1").asText();
      var dep=dependency(jar,purl,sha,d.path("sha256").asText(),List.of(cve),Vulnerability.Source.valueOf(v.path("source").asText()));
      analyzer.apply(dep,engine,before);check(dep.getVulnerabilitiesCount()==1,"preserved raw report has no already-filtered pair");
      analyzer.apply(dep,engine,after);boolean expected=(sha.equals(SHA1)&&CVES.contains(cve))||(sha.equals(kcSha1)&&cve.equals(kcCve));check((dep.getVulnerabilitiesCount()==0)==expected,"whole raw counterfactual only exact reviewed pairs");
      pairs++;unique.add(purl+" "+cve);if(expected){removed++;if(Set.of("HIGH","CRITICAL").contains(v.path("severity").asText()))highCriticalRemoved++;}else remaining.add(purl+" "+cve);
     }
    }
   }
   check(pairs==55&&unique.size()==47&&removed==3&&highCriticalRemoved==2&&remaining.size()==44,"exact C6 raw delta only");
   String result="{\"result\":\"PASS\",\"checks\":"+checks+",\"actualEngine\":\"ODC13 XSD parser and VulnerabilitySuppressionAnalyzer\",\"selectorSemantics\":\"exact archive SHA1 AND named CVE\",\"rawOccurrences\":55,\"rawUniquePairs\":47,\"counterfactualRemovedOccurrences\":3,\"counterfactualRemovedHighCritical\":2,\"counterfactualRemainingUniquePairs\":44,\"providerCalls\":0,\"engineAnalysisOrUpdatesInvoked\":false,\"sharedXmlChanged\":false}";
   Files.writeString(output,result+"\n");System.out.println(result);
  }
 }
}
