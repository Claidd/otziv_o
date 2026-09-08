import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { BASELINE_MANIFEST_SHA256, reviewedImageSet, reviewedImageSetForComponent,
  supplementalReviewedSources, validateReviewedImageSet } from './reviewed-image-sets.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';
import { assertPublicationSet } from './reviewed-image-sets.mjs';
import { resolveActivationManifest, validateReviewedDefaults } from './reviewed-image-defaults.mjs';

const baselineBytes = await readFile(new URL('./reviewed-images.json', import.meta.url));
const baseline = JSON.parse(baselineBytes);
const definitions = [
  ['mc', 'infrastructure/runtime-security/builds/minio', 'mc.Dockerfile'],
  ['minio', 'infrastructure/runtime-security/builds/minio', 'server.Dockerfile'],
  ['postgres', 'infrastructure/runtime-security/builds/postgres-c14', 'Dockerfile'],
];
const bytes = value => Buffer.from(JSON.stringify(value));

function candidate(component, context, dockerfile) {
  const original = [...baseline.images, ...supplementalReviewedSources()].find(item => item.component === component);
  return { schema: baseline.schema, repository: baseline.repository, publicationSet: 'c14-' + component,
    baselineManifestSha256: BASELINE_MANIFEST_SHA256, images: [{ component, context,
      dockerfile: context + '/' + dockerfile, dockerfileSha256: 'a'.repeat(64), platform: 'linux/amd64',
      sourceBeforeRef: original.sourceBeforeRef, defaultReferencesBefore: structuredClone(original.defaultReferencesBefore),
      candidateBaseRef: 'debian@sha256:' + 'b'.repeat(64), buildArgs: {} }] };
}

for (const definition of definitions) {
  const [component] = definition, name = 'c14-' + component;
  test(name + ' validates a single fixed publication component and original service coverage', () => {
    const manifest = candidate(...definition);
    const selected = reviewedImageSet(name);
    assert.equal(selected.path, 'infrastructure/runtime-security/reviewed-images-' + name + '.json');
    assert.deepEqual(reviewedImageSetForComponent(component), selected);
    const validated = validateReviewedImageSet(name, bytes(manifest), baselineBytes);
    assert.deepEqual(validateManifest(validated).map(image => image.component), [component]);
    assertPublicationSet({ manifestSet: name, manifestPath: selected.path }, name);
  });

  for (const [label, mutate] of Object.entries({
    'different component': m => { m.images[0].component = 'nginx'; },
    'different context': m => { m.images[0].context += '/nested'; },
    'different Dockerfile': m => { m.images[0].dockerfile += '.other'; },
    'source replaced by candidate': m => { m.images[0].sourceBeforeRef = m.images[0].candidateBaseRef; },
    'source service removed': m => { m.images[0].defaultReferencesBefore.pop(); },
    'extra production service': m => { m.images[0].defaultReferencesBefore.push({ path: 'docker-compose.yaml', service: 'unreviewed' }); },
    'second image': m => { m.images.push(structuredClone(m.images[0])); },
    'host preparation': m => { m.images[0].prepare = { kind: 'shell' }; },
    'other named set': m => { m.publicationSet = 'c12-phpmyadmin'; },
    'historical manifest replaced': m => { m.baselineManifestSha256 = 'c'.repeat(64); },
  })) test(name + ' rejects ' + label, () => {
    const manifest = candidate(...definition); mutate(manifest);
    assert.throws(() => validateReviewedImageSet(name, bytes(manifest), baselineBytes), /reviewed_image_set_/);
  });

  test(name + ' publication receipt cannot be substituted into a different set', () => {
    const receipt = { manifestSet: name, manifestPath: reviewedImageSet(name).path };
    assert.throws(() => assertPublicationSet(receipt, 'baseline'), /manifest_set_mismatch/);
    const other = component === 'mc' ? 'c14-minio' : 'c14-mc';
    assert.throws(() => assertPublicationSet(receipt, other), /manifest_set_mismatch/);
  });
}

test('publication selection rejects inherited names and object coercion', () => {
  for (const value of ['__proto__', 'constructor', ['c14-mc'], { toString: () => 'c14-mc' }]) {
    assert.throws(() => reviewedImageSet(value), /reviewed_image_set_unknown/);
  }
});

test('mc and MinIO defaults need their own complete publication proof and cannot disappear from coverage', async () => {
  const originals = [...baseline.images, ...supplementalReviewedSources()];
  const rows = originals.map(image => ({ image: image.sourceBeforeRef.replace(/:[^/@]+@/, '@'),
    references: structuredClone(image.defaultReferencesBefore) }));
  const readNothing = async () => { throw Error('unexpected_evidence_read'); };
  assert.equal((await validateReviewedDefaults(rows, baselineBytes, undefined, readNothing, true)).length, 32);
  for (const component of ['mc', 'minio']) {
    const index = originals.findIndex(image => image.component === component);
    await assert.rejects(resolveActivationManifest(originals[index], {}, baselineBytes, readNothing), /versioned_manifest_required/);
    const changed = structuredClone(rows);
    changed[index].image = 'ghcr.io/claidd/otziv-security@sha256:' + 'd'.repeat(64);
    await assert.rejects(validateReviewedDefaults(changed, baselineBytes, undefined, readNothing, true), /unregistered_reference/);
    const missing = rows.filter((_, position) => position !== index);
    await assert.rejects(validateReviewedDefaults(missing, baselineBytes, undefined, readNothing, true), /service_missing/);
  }
});

test('new publication sets preserve C7 bytes and have no implicit database activation', async () => {
  validateReviewedImageSet('baseline', baselineBytes);
  const rows = baseline.images.map(image => ({ image: image.sourceBeforeRef.replace(/:[^/@]+@/, '@'),
    references: structuredClone(image.defaultReferencesBefore) }));
  const postgres = baseline.images.findIndex(image => image.component === 'postgres');
  rows[postgres].image = 'ghcr.io/claidd/otziv-security@sha256:' + 'c'.repeat(64);
  await assert.rejects(validateReviewedDefaults(rows, baselineBytes, undefined,
    async () => { throw Error('database_change_must_be_rejected_before_proof'); }), /database_coordinated_transition_required/);
  const workflow = await readFile(new URL('../../.github/workflows/quality-gates.yml', import.meta.url), 'utf8');
  for (const [component] of definitions) assert.match(workflow, new RegExp('          - c14-' + component + '\\r?\\n'));
});
