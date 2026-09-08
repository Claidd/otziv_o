import test from 'node:test';
import assert from 'node:assert/strict';
import { selectMonitoringSource, assertDistinctMonitoringImageIds, readMonitoringSource } from './monitoring-source.mjs';
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
