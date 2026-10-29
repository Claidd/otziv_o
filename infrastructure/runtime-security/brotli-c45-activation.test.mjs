import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {gunzipSync} from 'node:zlib';
import {hash} from './ssl-refresh.mjs';
import {validateBrotliC45Runtime, validateBrotliC45Rehearsal, couplePostgresC36WithKeycloakC45,
  C43_REFERENCE} from './brotli-c45-activation.mjs';
import {C36_POSTGRES} from './guava-c43-activation.mjs';

const prefix = new URL('./proofs/c45-keycloak/', import.meta.url);
const json = async name => JSON.parse(await readFile(new URL(name, prefix)));
const actual = {
  runtime: JSON.parse(gunzipSync(await readFile(new URL('inspection/runtime.json.gz', prefix)))),
  receipt: await json('inspection/inspection.json'), publication: await json('publication/publication.json'),
};
const packageBytes = await readFile(new URL('./c45-brotli-package.json', import.meta.url));
const fixture = () => structuredClone(actual);

test('C45 permits only the exact official Brotli payload and package bookkeeping', () => {
  const value = fixture();
  assert.deepEqual(validateBrotliC45Runtime(value.runtime, value.receipt, value.publication, packageBytes), value.receipt.changedFiles);
});

for (const [name, mutate, error] of [
  ['another parent', value => { value.runtime.parent.reference = 'other'; }, 'parent'],
  ['another candidate image', value => { value.runtime.candidate.imageId = 'other'; }, 'image_identity'],
  ['replaced parent layer', value => { value.runtime.candidate.rootfs[0] = 'other'; }, 'parent_layers'],
  ['root runtime user', value => { value.runtime.candidate.configuration.User = '0'; }, 'unrelated_configuration'],
  ['executed inspected image', value => { value.runtime.candidate.containerExecuted = true; }, 'executed_container'],
  ['wrong decoder bytes', value => { value.runtime.candidate.inventory['usr/lib/x86_64-linux-gnu/libbrotlidec.so.1.0.9'].sha256 = 'wrong'; }, 'official_payload'],
  ['wrong library permissions', value => { value.runtime.candidate.inventory['usr/lib/x86_64-linux-gnu/libbrotlidec.so.1.0.9'].mode = 511; }, 'official_payload'],
  ['wrong package control', value => { value.runtime.candidate.inventory['var/lib/dpkg/info/libbrotli1:amd64.shlibs'].sha256 = 'wrong'; }, 'official_control'],
  ['forged captured status', value => { value.runtime.candidate.captured['var/lib/dpkg/status'] += '\n'; }, 'captured_metadata_digest'],
  ['unrelated package change', value => {
    const image = value.runtime.candidate, path = 'var/lib/dpkg/status';
    image.captured[path] = image.captured[path].replace(/(Package: libc6\n[\s\S]*?\nVersion: )[^\n]+/, '$1other');
    image.inventory[path].sha256 = hash(Buffer.from(image.captured[path]));
  }, 'unrelated_package'],
  ['forged package ownership', value => {
    const image = value.runtime.candidate, path = 'var/lib/dpkg/info/libbrotli1:amd64.list';
    image.captured[path] += '/opt/keycloak/bin/kc.sh\n';
    image.inventory[path].sha256 = hash(Buffer.from(image.captured[path]));
  }, 'package_manifest'],
  ['provider replacement', value => { value.runtime.candidate.inventory['opt/keycloak/providers/otziv-security-generation.jar'].sha256 = 'wrong'; }, 'unrelated_payload'],
  ['directory permission change', value => { value.runtime.candidate.inventory['opt/keycloak'].mode = 511; }, 'unrelated_payload'],
  ['concealed changed file', value => { value.receipt.changedFiles.pop(); }, 'changed_files'],
]) test('C45 rejects ' + name, () => {
  const value = fixture(); mutate(value);
  assert.throws(() => validateBrotliC45Runtime(value.runtime, value.receipt, value.publication, packageBytes), new RegExp(error));
});

test('another package inventory cannot authorize different file bytes', () => {
  const value = fixture(), substituted = JSON.parse(packageBytes);
  substituted.payload['opt/keycloak/bin/kc.sh'] = substituted.payload['usr/lib/x86_64-linux-gnu/libbrotlidec.so.1.0.9'];
  assert.throws(() => validateBrotliC45Runtime(value.runtime, value.receipt, value.publication,
    Buffer.from(JSON.stringify(substituted))), /package_inventory_digest/);
});

const capture = await json('rehearsal/capture.json'), replay = await json('rehearsal/replay.json');
const rehearsalSha = hash(await readFile(new URL('executed-rehearsal.py.txt', prefix)));
test('actual C45 rehearsal preserves all 102 tables and both rollback paths on C43/C36', () => {
  validateBrotliC45Rehearsal(capture, replay, actual.publication, rehearsalSha);
});
for (const [name, mutate, error] of [
  ['historical schema substitution', value => { value.schema = 'otziv-guava-c43-rehearsal-v1'; }, 'rehearsal_schema'],
  ['another rollback issuer', value => { value.replayBaselineKeycloak = 'other'; }, 'replay_issuer'],
  ['missing table coverage', value => { value.tableCount = 101; }, 'table_coverage'],
  ['missing same-volume rollback', value => { value.checks = value.checks.filter(check => check.name !== 'same_volume_rollback_preserves_all_post_upgrade_writes'); }, 'missing_check'],
  ['resource leak', value => { value.ownedResourcesRemaining.container = 1; }, 'resource_leak'],
]) test('C45 data acceptance rejects ' + name, () => {
  const value = structuredClone(replay); mutate(value);
  assert.throws(() => validateBrotliC45Rehearsal(capture, value, actual.publication, rehearsalSha), new RegExp(error));
});

test('C45 refuses another database pair before reading issuer acceptance', async () => {
  for (const readiness of [{}, {postgresC36Acceptance: {}, postgresReference: 'other'},
    {postgresC36Acceptance: {}, postgresReference: C36_POSTGRES, postgresConfigId: 'other'}])
    await assert.rejects(couplePostgresC36WithKeycloakC45(readiness, {}, () => assert.fail('unexpected read')),
      /brotli_c45_database_(acceptance_missing|pair|config)/);
});

test('the actual publication binds full C45 acceptance to the accepted C43 parent', async () => {
  const {createEvidenceReader, validateActivation} = await import('./reviewed-image-defaults.mjs');
  const read = await createEvidenceReader(fileURLToPath(new URL('../../', import.meta.url)));
  const baseline = await read('infrastructure/runtime-security/reviewed-images.json');
  const entry = JSON.parse(await read('infrastructure/runtime-security/proofs/c45-keycloak/activation-candidate.json'));
  assert.equal(actual.runtime.parent.reference, C43_REFERENCE);
  await validateActivation(JSON.parse(baseline).images.find(image => image.component === 'keycloak'), entry, baseline, read);
  const unbound = structuredClone(entry);
  unbound.migrationAcceptance.sha256 = '0'.repeat(64);
  await assert.rejects(validateActivation(JSON.parse(baseline).images.find(image => image.component === 'keycloak'), unbound, baseline, read), /brotli_c45_migration_binding/);
});
