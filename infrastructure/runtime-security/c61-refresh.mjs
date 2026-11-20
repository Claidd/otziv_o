import assert from 'node:assert/strict';
import {dirname} from 'node:path';
import {validateC50ActivationScan} from './c50-refresh.mjs';

export const C61_PARENTS = Object.freeze({nginx: 'c59-nginx', phpmyadmin: 'c53-phpmyadmin'});
// Retain the complete accepted package inventory, with only the signed fixes replaced.
// Missing extensions or an unrelated package change fail even with a clean scan.
export async function validateC61ActivationScan(publication, publicationPath, read) {
  const parent = C61_PARENTS[publication.component];
  assert.ok(parent, 'c61_component');
  assert.equal(publication.manifestSet, 'c61-' + publication.component, 'c61_manifest');
  await validateC50ActivationScan(publication, publicationPath, read);
  const base = 'infrastructure/runtime-security/proofs/' + parent + '/publication/';
  const [previous, current] = await Promise.all([
    read(base + 'vulnerabilities.json'),
    read(dirname(publicationPath).replaceAll('\\', '/') + '/vulnerabilities.json'),
  ]);
  const inventory = bytes => JSON.parse(bytes).Results.filter(r => r.Class === 'os-pkgs')
    .flatMap(r => r.Packages || []).map(p => [p.Name, [p.Version, p.Release].filter(Boolean).join('-')]);
  const expected = inventory(previous).map(([name, version]) => [name,
    name === 'libxml2' ? '2.13.9-r3' : name.startsWith('php83') && version === '8.3.36-r0' ? '8.3.37-r0' : version]);
  assert.ok(expected.some(([name]) => name === 'libxml2'), 'c61_parent_xml');
  assert.deepEqual(inventory(current).sort(), expected.sort(), 'c61_package_inventory');
}
