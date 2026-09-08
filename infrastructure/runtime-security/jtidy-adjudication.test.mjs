import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';
import test from 'node:test';

const base = fileURLToPath(new URL('./proofs/jtidy/', import.meta.url));
const read = (path) => readFile(resolve(base, path));
const json = async (path) => JSON.parse(await read(path));
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const sha1 = 'e57994fdeb7077b11a8ba05c93e3cb89c3ad8ed0';
const sha256 = '7cea4360710adb44c588e13afdcdc0171fc1a3e61cc4562703ddb1f0a36f0ca4';
const cve = 'CVE-2023-34623';

test('JTidy correction remains one exact archive, one advisory and an unexpired date', async () => {
  const current = (await readFile(new URL('./maven-false-positives.xml', import.meta.url))).toString();
  const rules = [...current.matchAll(/<suppress(?:\s[^>]*)?>[\s\S]*?<\/suppress>/g)].map(([rule]) => rule);
  const selected = rules.filter((rule) => rule.includes(`<sha1>${sha1}</sha1>`));
  assert.equal(selected.length, 1);
  assert.match(selected[0], /^<suppress until="2027-01-01Z">/);
  assert.equal([...selected[0].matchAll(/<cve>/g)].length, 1);
  assert.ok(selected[0].includes(`<cve>${cve}</cve>`));
  assert.doesNotMatch(selected[0], /<(?:packageUrl|filePath|cpe|cwe|cvss\w*Below|vulnerabilityName)(?:\s|>)/);
  const historical = (await read('after.xml')).toString();
  assert.ok(historical.includes(selected[0]), 'changing the reviewed rule requires fresh proof');
  assert.ok(Date.now() < Date.parse('2027-01-01T00:00:00Z'), 'JTidy adjudication has expired; review required');
});

test('JTidy immutable proof inputs and outputs retain every captured byte', async () => {
  const review = await json('review.json');
  assert.equal(review.artifact.sha256, sha256);
  assert.equal(review.hosted.artifactId, 10049722566);
  assert.equal(review.hosted.downloadDigestVerified, true);
  for (const [path, expected] of Object.entries(review.files)) {
    assert.equal(digest(await read(path)), expected, `frozen evidence changed: ${path}`);
  }
});

test('JTidy raw C13 reports preserve both identical HIGH findings before adjudication', async () => {
  const review = await json('review.json');
  let pairs = 0, occurrences = 0;
  for (const report of review.hosted.reports) {
    const raw = gunzipSync(await read(report.file));
    assert.equal(digest(raw), report.rawSha256);
    for (const dependency of JSON.parse(raw).dependencies) {
      for (const vulnerability of dependency.vulnerabilities ?? []) {
        occurrences++;
        if (dependency.sha1 === sha1 && vulnerability.name === cve) {
          assert.equal(dependency.sha256, sha256);
          assert.ok(dependency.packages.some(({ id }) => id === 'pkg:maven/com.github.jtidy/jtidy@1.0.5'));
          assert.equal(vulnerability.severity, 'HIGH');
          pairs++;
        }
      }
    }
  }
  assert.equal(review.hosted.reports.length, 6);
  assert.equal(occurrences, 34);
  assert.equal(pairs, 2);
});

test('JTidy actual ODC13 evidence covers full raw records and counterexamples', async () => {
  const result = await json('selector-result.json');
  assert.equal(result.result, 'PASS');
  assert.equal(result.checks, 94);
  assert.equal(result.engineJar, 'dependency-check-core-13.0.0.jar');
  assert.equal(result.beforeXmlSha256, digest(await read('before.xml')));
  assert.equal(result.afterXmlSha256, digest(await read('after.xml')));
  assert.equal(result.artifactSha256, sha256);
  assert.equal(result.rawOccurrences, 34);
  assert.equal(result.correctedOccurrences, 2);
  assert.equal(result.reports.length, 6);
  assert.equal(result.reports.reduce((sum, report) => sum + report.correctedOccurrences, 0), 2);
  assert.equal(result.reports.reduce((sum, report) => sum + report.highCriticalAfter, 0), 0);
  assert.deepEqual(result.sourcesTested, ['NVD', 'OSSINDEX']);
  assert.deepEqual(result.negativeCases, ['other CVE', 'altered actual JAR same PURL', 'old r938', 'other version', 'missing artifact/hash', 'expired rule']);
  assert.equal(result.providerCalls, 0);
  assert.equal(result.fullAudit, false);
});

test('JTidy fresh exact-binary runtime controls distinguish vulnerable and fixed HTML parsing', async () => {
  const executions = await json('execution-records.json');
  for (const record of executions) {
    assert.equal(record.exitCode, record.expectedExitCode, record.label);
    assert.equal(digest(await read(`runtime-logs/${record.label}.stdout.log`)), record.stdoutSha256);
    assert.equal(digest(await read(`runtime-logs/${record.label}.stderr.log`)), record.stderrSha256);
  }
  assert.equal(executions.find(({ label }) => label === 'r938-excessive').exitCode, 42);
  assert.equal(executions.find(({ label }) => label === '1.0.5-excessive').exitCode, 0);
  for (const version of ['r938', '1.0.5']) {
    assert.match((await read(`runtime-logs/${version}-ordinary.stdout.log`)).toString(), /PASS ordinary HTML preserved/);
  }
  assert.match((await read('runtime-logs/1.0.5-excessive.stdout.log')).toString(), /PASS controlled excessive-nesting rejection/);
  assert.match((await read('runtime-logs/r938-excessive.stdout.log')).toString(), /StackOverflowError/);
});

test('JTidy source and actual bytecode prove the HTML guard without a blanket XML claim', async () => {
  const source = (await read('primary/ParserImpl.java')).toString();
  assert.equal(digest(await read('primary/ParserImpl.java')), 'd67018876a982190a67ff949c844f8f80f0c1e16a3617319e68961b2a7fa7db0');
  assert.match(source, /MAX_NESTING_LEVEL = 1000/);
  assert.match(source, /if \(nestingLevel > MAX_NESTING_LEVEL\)[\s\S]{0,70}throw new ExcessiveNesting/);
  assert.match(source, /node\.tag\.getParser\(\)\.parse\(lexer, node, mode, nestingLevel \+ 1\)/);
  assert.match((await read('1.0.5-ParserImpl.javap.txt')).toString(), /sipush\s+1000[\s\S]{0,200}org\/w3c\/tidy\/ExcessiveNesting/);
  assert.doesNotMatch((await read('r938-ParserImpl.javap.txt')).toString(), /ExcessiveNesting/);
  const readme = (await read('README.md')).toString();
  assert.match(readme, /parseXMLElement/);
  assert.match(readme, /XML safety[\s\S]{0,100}not established/);
  assert.equal((await readdir(resolve(base, 'raw-c13'))).length, 6);
});
