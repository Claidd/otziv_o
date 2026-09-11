import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

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

export function assertDistinctMonitoringImageIds(sourceId, candidateId) {
  assert.match(sourceId || '', IMAGE_ID, 'monitoring_source_identity_invalid');
  assert.match(candidateId || '', IMAGE_ID, 'monitoring_candidate_identity_invalid');
  assert.notEqual(sourceId, candidateId, 'monitoring_source_equals_candidate');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv.length, 3, 'usage_monitoring_source_component');
  console.log((await readMonitoringSource(process.argv[2])).source);
}
