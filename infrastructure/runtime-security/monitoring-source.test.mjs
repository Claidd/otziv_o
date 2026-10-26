import test from 'node:test';
import assert from 'node:assert/strict';
import { selectMonitoringSource, assertDistinctMonitoringImageIds, readMonitoringSource, usesReviewedSslOverlay,
  isPublishedMonitoringOverlayManifest } from './monitoring-source.mjs';
const before = 'prom/prometheus@sha256:'+'a'.repeat(64), after='ghcr.io/claidd/otziv-security@sha256:'+'b'.repeat(64);
const manifest = () => ({schema:'otziv-reviewed-images-v1', images:[{component:'prometheus',sourceBeforeRef:before,candidateBaseRef:after,publishedDigest:after}]});
for(const component of ['prometheus','grafana','loki','tempo','alloy'])test(`immutable historical baseline is used for ${component} after default/publication switch`,()=>{
 const m=manifest();m.images[0].component=component;
 assert.equal(selectMonitoringSource(m,component),before);
 m.images[0].publishedDigest='ghcr.io/claidd/otziv-security@sha256:'+'c'.repeat(64);
 m.images[0].defaultReferencesBefore=[{image:after}];
 assert.equal(selectMonitoringSource(m,component),before);
});
for(const [name,change]of Object.entries({
 missing:m=>m.images=[], duplicate:m=>m.images.push(structuredClone(m.images[0])), schema:m=>m.schema='other',
 mutable:m=>m.images[0].sourceBeforeRef='prom/prometheus:latest', bare:m=>m.images[0].sourceBeforeRef='sha256:'+'a'.repeat(64),
 newline:m=>m.images[0].sourceBeforeRef=before+'\n', missingPin:m=>delete m.images[0].sourceBeforeRef
}))test(`reject ${name} historical source rather than fall back to current Compose`,()=>{
 const m=manifest();change(m);assert.throws(()=>selectMonitoringSource(m,'prometheus'));
});
test('unsupported components cannot start a monitoring scenario',()=>{
 assert.throws(()=>selectMonitoringSource(manifest(),'arbitrary'));
});
test('different registry references resolving to the same actual local ID are rejected',()=>{
 const id='sha256:'+'d'.repeat(64);assert.throws(()=>assertDistinctMonitoringImageIds(id,id),/monitoring_source_equals_candidate/);
});
test('different actual immutable image IDs are accepted, malformed identities are not',()=>{
 assertDistinctMonitoringImageIds('sha256:'+'d'.repeat(64),'sha256:'+'e'.repeat(64));
 for(const id of [null,'latest','short','sha256:'+'d'.repeat(63)])assert.throws(()=>assertDistinctMonitoringImageIds(id,'sha256:'+'e'.repeat(64)));
});
test('real checked-in manifest loads without any Compose file or environment interpolation',async()=>{
 const value=await readMonitoringSource('prometheus');assert.match(value.source,/^prom\/prometheus@sha256:[a-f0-9]{64}$/);assert.match(value.manifestSha256,/^[a-f0-9]{64}$/);
});

test('every activated C29 monitoring image selects its verified published runtime for configuration and scan proofs',async()=>{
 for(const component of ['prometheus','loki','tempo','grafana','alloy'])assert.equal(await usesReviewedSslOverlay(component),true);
 await assert.rejects(usesReviewedSslOverlay('postgres'),/monitoring_component_invalid/);
});
test('C30 Grafana remains on the published image verification path when activated',()=>{
 const path='infrastructure/runtime-security/reviewed-images-c30-grafana.json';
 assert.equal(isPublishedMonitoringOverlayManifest('grafana',path),true);
 assert.equal(isPublishedMonitoringOverlayManifest('tempo',path),false);
 assert.equal(isPublishedMonitoringOverlayManifest('grafana','infrastructure/runtime-security/reviewed-images-c30-tempo.json'),false);
});

test('C40 Brotli refresh verifies the published Grafana image without selecting other components',()=>{
 const path='infrastructure/runtime-security/reviewed-images-c40-grafana.json';
 assert.equal(isPublishedMonitoringOverlayManifest('grafana',path),true);
 for(const component of ['prometheus','loki','tempo','alloy'])assert.equal(isPublishedMonitoringOverlayManifest(component,path),false);
 assert.equal(isPublishedMonitoringOverlayManifest('grafana','infrastructure/runtime-security/reviewed-images-c40-tempo.json'),false);
});

test('C41 curl refresh keeps Grafana on the exact published image verification path',()=>{
 const path='infrastructure/runtime-security/reviewed-images-c41-grafana.json';
 assert.equal(isPublishedMonitoringOverlayManifest('grafana',path),true);
 for(const component of ['prometheus','loki','tempo','alloy'])assert.equal(isPublishedMonitoringOverlayManifest(component,path),false);
 assert.equal(isPublishedMonitoringOverlayManifest('grafana','infrastructure/runtime-security/reviewed-images-c41-tempo.json'),false);
});
