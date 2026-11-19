import assert from 'node:assert/strict';
import {dirname} from 'node:path';
import {C50_GO_TARGETS, validateC50ActivationScan} from './c50-refresh.mjs';

export const C55_COMPONENTS = Object.freeze(Object.keys(C50_GO_TARGETS));

// Keep every C50 package and raw-report requirement, and independently require
// the fixed standard library in every executable, even without a scanner finding.
export async function validateC55ActivationScan(publication, publicationPath, read) {
  assert.ok(C55_COMPONENTS.includes(publication.component), 'c55_component');
  await validateC50ActivationScan(publication, publicationPath, read);
  const raw = JSON.parse(await read(dirname(publicationPath).replaceAll('\\', '/') + '/vulnerabilities.json'));
  const binaries = (raw.Results || []).filter(result => result.Type === 'gobinary');
  assert.ok(binaries.length >= C50_GO_TARGETS[publication.component].length, 'c55_binary_coverage');
  for (const binary of binaries) {
    const standardLibrary = (binary.Packages || []).filter(pkg => pkg.Name === 'stdlib');
    assert.equal(standardLibrary.length, 1, 'c55_stdlib_coverage:' + binary.Target);
    assert.equal(standardLibrary[0].Version, 'v1.27.4', 'c55_stdlib_version:' + binary.Target);
  }
}
