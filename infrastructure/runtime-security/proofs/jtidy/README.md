# JTidy CVE-2023-34623: exact artifact adjudication

The two failing C13 build-support findings identify the same Maven Central
`com.github.jtidy:jtidy:1.0.5` JAR. Its HTML parser already contains the upstream
fix for the reported excessive nesting condition. This corrects an affected
version match; it does not accept an unresolved runtime vulnerability.

The single added rule in `../../maven-false-positives.xml` selects only archive
SHA-1 `e57994fdeb7077b11a8ba05c93e3cb89c3ad8ed0` AND `CVE-2023-34623` and expires
on **2027-01-01 UTC**. SHA-256 is
`7cea4360710adb44c588e13afdcdc0171fc1a3e61cc4562703ddb1f0a36f0ca4`.
The ODC schema has one artifact selector; the reviewed PURL and SHA-256 in the
notes are provenance, not additional XML predicates. Existing rules, POMs,
analyzers, severity thresholds and required CI checks are unchanged.

## Primary sources and artifact identity

- [Original issue 4](https://github.com/trajano/jtidy/issues/4) uses
  `net.sf.jtidy:jtidy:r938`, nested HTML `div` elements and the
  `ParseBlock.parse` → `parseTag` recursion path.
- [Maintainer issue 63](https://github.com/jtidy/jtidy/issues/63#issuecomment-1693710427)
  explains the false comparison between historical revision r938 and release
  1.0.4. Its collaborator-authored API response is retained in `primary/`.
- [Immutable 1.0.5 source](https://github.com/jtidy/jtidy/blob/d2f8dd407899c005c12a9df1b5db807bbc0c79c8/src/main/java/org/w3c/tidy/ParserImpl.java)
  and the official Central source JAR have the same `ParserImpl.java` bytes,
  SHA-256 `d67018876a982190a67ff949c844f8f80f0c1e16a3617319e68961b2a7fa7db0`.
  `MAX_NESTING_LEVEL=1000` is checked before recursive parser dispatch; the
  HTML parse entry catches `ExcessiveNesting` and reports a controlled error.
- Fresh `javap` of the actual flagged JAR confirms the limit, conditional
  exception, depth increment and HTML catch in bytecode. Both the older r938
  and fixed 1.0.5 disassemblies are retained. The local JAR, earlier C7 runtime
  fixture and both C13 report entries have identical SHA-1/SHA-256.

The dependency is used by Maven plugin descriptor generation:
`maven-plugin-tools-generators:3.15.2`'s `PluginDescriptorFilesGenerator` calls
`GeneratorUtils.makeHtmlValid`, which calls `Tidy.parse`. Therefore no claim
that JTidy is removable or unreachable is used to justify the correction.
It is a build plugin dependency, not an application runtime parser.

## Actual engine and runtime checks

`JTidySuppressionProof.java` directly invokes unmodified ODC **13.0.0**
`SuppressionParser`/XSD and `VulnerabilitySuppressionAnalyzer`, using
`Engine.Mode.EVIDENCE_PROCESSING`. No Maven goal, database update, Sonatype
request or other full analyzer pass is involved. The engine JAR hash is in
`selector-result.json`; all supplied classpath JAR hashes are retained.

**94 checks passed.** The actual old XML remains red, and the new XML corrects
only the named pair. Real-engine negative cases cover another CVE, changed
archive bytes with the same PURL, the actual vulnerable r938 JAR, another
version, missing archive/hash and an expired rule. NVD and OSS Index
vulnerability sources are tested. The analyzer's lazy hash calculation is
tested on the actual file. Corrected findings remain in the engine's
suppressed-vulnerability collection.

All six complete C13 JSON reports are preserved as lossless gzip files in
`raw-c13/`. They came from artifact **10049722566** of
[PR run 34210143979, attempt 1](https://github.com/Claidd/otziv_o/actions/runs/34210143979),
head `dcfc344561c2e2acd9be60559f4973ca36aa2b82`, Maven job **102009243409**.
The downloaded ZIP SHA-256 matched GitHub artifact metadata. The real-engine
counterfactual processes every reported vulnerability: **34 occurrences**,
exactly **2 duplicate HIGH occurrences** corrected, **32 retained**. Both
build-support reports become free of blocking findings in this counterfactual;
the four other reports retain their previous outcome. Raw reports are not
rewritten or described as a new successful authenticated audit.

The same bounded Java test was also freshly compiled and executed against
both actual JARs under JDK 26. Ordinary HTML succeeds on both. Identical HTML
at depth 8192 causes the old artifact's caught `StackOverflowError` (expected
exit 42); 1.0.5 returns one controlled parse error and no rendered output.
Each runtime uses a 64 MiB heap, 2 MiB stack and 30-second process timeout.
`execution-records.json` and `runtime-logs/` retain fresh results.
`prior-c7-runtime.json` is explicitly older independent runtime evidence.

## Reproduction and limits

The retained offline runner needs a local JDK, an ODC13 plugin realm classpath
file (platform-separated existing JAR paths) and the exact two vendor JARs:

```sh
python infrastructure/runtime-security/proofs/jtidy/run-proof.py \
  --java-home /path/to/jdk \
  --classpath-file /path/to/odc13-classpath.txt \
  --fixed-jar /path/to/jtidy-1.0.5.jar \
  --old-jar /path/to/jtidy-r938.jar \
  --out /path/to/separate/scratch
```

The runner verifies artifact hashes before compiling, uses no Maven/network
operation and keeps execution output outside frozen evidence. It compares the
current repository rule set with the retained pre-change baseline; subsequent
intentional rule changes require a separately reviewed proof, not overwriting
this historical result. The Node test checks capture hashes, current exact
rule and expiry, primary HTML guard and recorded real-engine/runtime coverage.
It is an integrity check, not a replacement implementation of ODC semantics.

The decision covers the specific **HTML excessive-nesting defect** in issue 4.
`parseXMLElement` has a separate recursion path; XML safety, arbitrary JVM
stack sizes and absence of all other parser defects are not established.
A fresh authenticated hosted audit on the new commit is still required.
The independent MinIO/mc/MySQL/PostgreSQL upstream gate failures are unchanged.
