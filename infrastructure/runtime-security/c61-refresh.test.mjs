import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {C61_PARENTS, validateC61ActivationScan} from './c61-refresh.mjs';
import {validateReviewedImageSet} from './reviewed-image-sets.mjs';
import {summarizeReport, TRIVY_IMAGE} from './scan.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';

const read = path => readFile(new URL('../../' + path, import.meta.url));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
async function fixture(component) {
  const folder = 'infrastructure/runtime-security/proofs/' + C61_PARENTS[component] + '/publication/';
  const publication = JSON.parse(await read(folder + 'publication.json'));
  const raw = JSON.parse(await read(folder + 'vulnerabilities.json'));
  const receipt = JSON.parse(await read(folder + 'vulnerabilities.adjudications.json'));
  publication.manifestSet = 'c61-' + component;
  const os = raw.Results.find(r => r.Class === 'os-pkgs');
  for (const pkg of os.Packages) {
    if (pkg.Name === 'libxml2') pkg.Version = '2.13.9-r3';
    if (pkg.Name.startsWith('php83') && pkg.Version === '8.3.36-r0') pkg.Version = '8.3.37-r0';
  }
  const current = 'infrastructure/runtime-security/proofs/c61-' + component + '/publication/';
  const validate = () => {
    const bytes = Buffer.from(JSON.stringify(raw));
    receipt.rawReportSha256 = hash(bytes);
    publication.security = {...combinedScanSummary(summarizeReport(raw), {grafana: receipt, alloy: receipt.alloy}), scannerImage: TRIVY_IMAGE};
    return validateC61ActivationScan(publication, current + 'publication.json', path =>
      path === current + 'vulnerabilities.json' ? bytes : path === current + 'vulnerabilities.adjudications.json' ?
        Buffer.from(JSON.stringify(receipt)) : read(path));
  };
  return {raw, os, validate};
}

for (const component of Object.keys(C61_PARENTS)) {
  test('C61 ' + component + ' published digest passes the exact activation scan', async () => {
    const folder = 'infrastructure/runtime-security/proofs/c61-' + component + '/publication/';
    const publication = JSON.parse(await read(folder + 'publication.json'));
    await validateC61ActivationScan(publication, folder + 'publication.json', read);
  });
  test('C61 ' + component + ' permits only the fixes in the accepted inventory', async () => {
    await (await fixture(component)).validate();
  });
  test('C61 ' + component + ' rejects old libxml2 even with a clean scanner result', async () => {
    const f = await fixture(component);
    f.os.Packages.find(p => p.Name === 'libxml2').Version = '2.13.9-r2';
    await assert.rejects(f.validate(), /c61_package_inventory/);
  });
  test('C61 ' + component + ' rejects missing packages and unrelated upgrades', async () => {
    const f = await fixture(component);
    f.os.Packages.pop();
    await assert.rejects(f.validate(), /c61_package_inventory/);
    const g = await fixture(component);
    g.os.Packages.find(p => p.Name === 'busybox').Version = '999-r0';
    await assert.rejects(g.validate(), /c61_package_inventory/);
  });
  test('C61 ' + component + ' preserves blocking high findings', async () => {
    const f = await fixture(component);
    f.os.Vulnerabilities = [{VulnerabilityID: 'CVE-2099-1234', Severity: 'HIGH', FixedVersion: '2'}];
    await assert.rejects(f.validate(), /c50_raw_findings/);
  });
  test('C61 ' + component + ' recipe remains bound to its accepted parent and service scope', async () => {
    const baseline = await read('infrastructure/runtime-security/reviewed-images.json');
    const bytes = await read('infrastructure/runtime-security/reviewed-images-c61-' + component + '.json');
    const image = validateReviewedImageSet('c61-' + component, bytes, baseline).images[0];
    const parent = JSON.parse(await read('infrastructure/runtime-security/proofs/' + C61_PARENTS[component] + '/publication/publication.json'));
    assert.equal(image.candidateBaseRef, parent.reference);
    const recipe = (await read(image.dockerfile)).toString().replaceAll('\r\n', '\n');
    assert.equal(hash(recipe), image.dockerfileSha256);
    assert.deepEqual(recipe.match(/^FROM .+$/gm), ['FROM ' + parent.reference]);
    assert.equal(recipe.match(/^(COPY|ADD|ARG|ENV|USER|ENTRYPOINT|CMD) /gm), null);
    assert.equal(recipe.match(/apk (upgrade|del|fix)/g), null);
    assert.ok(recipe.includes('cmp /tmp/c61.packages.before /tmp/c61.packages.after;'));
    assert.ok(recipe.includes('cmp /tmp/c61.payload.before /tmp/c61.payload.after;'));
  });
}
test('C61 rejects a PHP extension left on the vulnerable version', async () => {
  const f = await fixture('phpmyadmin');
  f.os.Packages.find(p => p.Name === 'php83-mysqli').Version = '8.3.36-r0';
  await assert.rejects(f.validate(), /c61_package_inventory/);
});
