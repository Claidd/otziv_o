import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,appendFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {repositoryInventory} from './upstream-images.mjs';

const COMPONENTS = new Set(['prometheus', 'grafana', 'loki', 'tempo', 'alloy']);
const IMMUTABLE_REF = /^[a-z0-9./:_-]+@sha256:[a-f0-9]{64}$/;
const IMAGE_ID = /^sha256:[a-f0-9]{64}$/;

export function selectMonitoringSource(manifest, component) {
  assert.ok(COMPONENTS.has(component), 'monitoring_component_invalid');
  assert.equal(manifest.schema, 'otziv-reviewed-images-v1', 'monitoring_manifest_schema');
  assert.ok(Array.isArray(manifest.images), 'monitoring_manifest_images_missing');
  const rows = manifest.images.filter(image => image.component === component);
  assert.equal(rows.length, 1, 'monitoring_source_missing_or_duplicate');
  assert.match(rows[0].sourceBeforeRef || '', IMMUTABLE_REF, 'monitoring_source_requires_historical_digest');
  return rows[0].sourceBeforeRef;
}

export async function readMonitoringSource(component) {
  const bytes = await readFile(new URL('./reviewed-images.json', import.meta.url));
  return { source: selectMonitoringSource(JSON.parse(bytes), component),
    manifestSha256: createHash('sha256').update(bytes).digest('hex') };
}

// C23 is a reviewed package overlay over the retained source-built C15 image.
// Its complete publication, parent recipe, filesystem, binary and scan graph must
// validate before CI can exercise the exact published overlay. Other recipes
// keep the existing source-build selection and every candidate is scanned fresh.
export async function usesReviewedSslOverlay(component) {
  assert.ok(COMPONENTS.has(component), 'monitoring_component_invalid');
  if (component !== 'alloy') return false;
  const index = JSON.parse(await readFile(new URL('./reviewed-image-activations.json', import.meta.url)));
  const entries = index.images.filter(entry => entry.component === component);
  assert.equal(entries.length, 1, 'monitoring_activation_missing_or_duplicate');
  if (entries[0].manifest?.path !== 'infrastructure/runtime-security/reviewed-images-c23-alloy.json') return false;
  const {validateRepositoryDefaults} = await import('./reviewed-image-defaults.mjs');
  await validateRepositoryDefaults(fileURLToPath(new URL('../../', import.meta.url)));
  return true;
}

export function assertDistinctMonitoringImageIds(sourceId, candidateId) {
  assert.match(sourceId || '', IMAGE_ID, 'monitoring_source_identity_invalid');
  assert.match(candidateId || '', IMAGE_ID, 'monitoring_candidate_identity_invalid');
  assert.notEqual(sourceId, candidateId, 'monitoring_source_equals_candidate');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const component = process.argv[2];
  assert.ok(COMPONENTS.has(component), 'monitoring_component_invalid');
  if (process.argv[3] === '--verification-mode') {
    assert.equal(process.argv.length, 4, 'usage_monitoring_verification_mode_component');
    const value = 'reviewed_ssl_overlay=' + String(await usesReviewedSslOverlay(component)) + '\n';
    assert.ok(process.env.GITHUB_OUTPUT, 'monitoring_verification_output_missing');
    await appendFile(process.env.GITHUB_OUTPUT, value);
  } else if (process.argv[3] === '--current') {
    assert.equal(process.argv.length, 4, 'usage_monitoring_source_component');
    const rows = (await repositoryInventory()).filter(row => row.references.some(ref =>
      ref.path === 'docker-compose.yaml' && ref.service === component));
    assert.equal(rows.length, 1, 'current_monitoring_image_missing_or_ambiguous');
    console.log(rows[0].image);
  } else {
    assert.equal(process.argv.length, 3, 'usage_monitoring_source_component');
    console.log((await readMonitoringSource(component)).source);
  }
}
