import assert from 'node:assert/strict';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {validateGuavaC43Runtime, couplePostgresC36WithKeycloakC43, C35_REFERENCE, C35_CONFIG, C36_POSTGRES, GUAVA_PATH, GUAVA_SHA256} from './guava-c43-activation.mjs';

function fixture() {
  const descriptor = sha256 => ({kind: 'file', mode: 436, uid: 1000, gid: 0, sha256});
  const provider = 'opt/keycloak/providers/otziv-security-generation.jar';
  const before = {reference: C35_REFERENCE, imageId: C35_CONFIG, rootfs: ['parent'],
    configuration: {User: '1000', Labels: {'com.otziv.publication.revision': 'a'.repeat(40)}},
    inventory: {[GUAVA_PATH]: descriptor('old'), [provider]: descriptor('provider'), '/unrelated': descriptor('original')}};
  const publication = {reference: 'ghcr.io/claidd/otziv-security@sha256:' + 'b'.repeat(64),
    imageId: 'sha256:' + 'c'.repeat(64), commit: 'd'.repeat(40), providerJarSha256: 'provider'};
  const after = structuredClone(before);
  after.reference = publication.reference;
  after.imageId = publication.imageId;
  after.rootfs.push('overlay');
  Object.assign(after.configuration.Labels, {'com.otziv.publication.revision': publication.commit,
    'otziv.guava': '33.7.2-jre', 'otziv.guava-advisory': 'CVE-2026-102554'});
  after.inventory[GUAVA_PATH].sha256 = GUAVA_SHA256;
  const runtime = {schema: 'otziv-keycloak-guava-c43-inventory-v1', parent: before, candidate: after};
  const receipt = {schema: 'otziv-keycloak-guava-c43-inspection-v1', result: 'PASS', inspectedContainersExecuted: false,
    parent: before.reference, parentImageId: before.imageId, candidate: after.reference, candidateImageId: after.imageId,
    changedFiles: [GUAVA_PATH], jar: {path: GUAVA_PATH, sha256: GUAVA_SHA256,
      pom: {groupId: 'com.google.guava', artifactId: 'guava', version: '33.7.2-jre'}}};
  return {runtime, receipt, publication};
}

test('C43 admits only the official Guava patch over the accepted issuer', () => {
  const value = fixture();
  assert.deepEqual(validateGuavaC43Runtime(value.runtime, value.receipt, value.publication), [GUAVA_PATH]);
});
for (const [name, mutate, error] of [
  ['unrelated file', value => { value.runtime.candidate.inventory['/unrelated'].sha256 = 'changed'; }, 'unrelated_payload'],
  ['provider change', value => { value.runtime.candidate.inventory['opt/keycloak/providers/otziv-security-generation.jar'].sha256 = 'changed'; }, 'unrelated_payload'],
  ['root layer replacement', value => { value.runtime.candidate.rootfs[0] = 'other'; }, 'parent_layers'],
  ['runtime user change', value => { value.runtime.candidate.configuration.User = '0'; }, 'unrelated_configuration'],
  ['jar byte substitution', value => { value.runtime.candidate.inventory[GUAVA_PATH].sha256 = 'wrong'; }, 'official_jar'],
  ['jar ownership change', value => { value.runtime.candidate.inventory[GUAVA_PATH].uid = 0; }, 'jar_metadata'],
  ['unexecuted proof substituted', value => { value.receipt.inspectedContainersExecuted = true; }, 'inspected_binary_executed'],
  ['wrong library identity', value => { value.receipt.jar.pom.version = '33.5.0-jre'; }, 'jar_identity'],
]) test(`C43 rejects ${name}`, () => {
  const value = fixture(); mutate(value);
  assert.throws(() => validateGuavaC43Runtime(value.runtime, value.receipt, value.publication), new RegExp(error));
});

test('C43 rejects an unproven or different database pair before loading issuer proofs', async () => {
  for (const readiness of [
    {},
    {postgresC36Acceptance: {}, postgresReference: 'other'},
    {postgresC36Acceptance: {}, postgresReference: C36_POSTGRES, postgresC36Pair: {keycloakReference: 'other'}},
  ]) await assert.rejects(couplePostgresC36WithKeycloakC43(readiness, {}, () => assert.fail('unexpected proof read')),
    /guava_c43_(database_acceptance_missing|database_pair|parent_database_pair)/);
});

test('the actual published C43 issuer passes the complete retained transition proof', async () => {
  const {createEvidenceReader, validateActivation} = await import('./reviewed-image-defaults.mjs');
  const read = await createEvidenceReader(fileURLToPath(new URL('../../', import.meta.url)));
  const baseline = await read('infrastructure/runtime-security/reviewed-images.json');
  const image = JSON.parse(baseline).images.find(item => item.component === 'keycloak');
  const entry = JSON.parse(await read('infrastructure/runtime-security/proofs/c43-keycloak/activation-candidate.json'));
  await validateActivation(image, entry, baseline, read);
});
