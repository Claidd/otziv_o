import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {validateC54Acceptance} from './postgres-c54-activation.mjs';
import {effectiveC54Summary} from './postgres-c54-adjudication.mjs';
import {summarizeReport} from './scan.mjs';

const folder = 'infrastructure/runtime-security/proofs/c54-postgres/';
const read = path => readFile(new URL('../../'+path,import.meta.url));
const json = async path => JSON.parse(await read(path));

test('the activated 17.12 credential database is the published and rehearsed image',async()=>{
  const index = await json('infrastructure/runtime-security/reviewed-image-activations.json');
  const entry = index.images.find(item=>item.component==='postgres');
  const accepted = await validateC54Acceptance(entry,read);
  const replay = await json(accepted.replayPath);
  assert.equal(replay.candidatePostgres,entry.reference);
  assert.equal(replay.candidateDatabase.serverVersion,'17.12');
  assert.equal(replay.ownedResourcesRemaining.container,0);
  const changed = structuredClone(entry);
  changed.databaseTransition = {...entry.databaseTransition,sha256:'0'.repeat(64)};
  await assert.rejects(validateC54Acceptance(changed,read));
});

test('unreviewed findings stay visible and a source advisory cannot authorize a different version',async()=>{
  const raw = await json(folder+'publication/vulnerabilities.json');
  const receipt = (await json(folder+'publication/vulnerabilities.adjudications.json')).postgres;
  const summary = summarizeReport(raw);
  const another = effectiveC54Summary({...summary,high:summary.high+1,unfixedHighOrCritical:summary.unfixedHighOrCritical+1},receipt);
  assert.equal(another.unresolvedRiskReview,'REQUIRED');
  assert.equal(another.effectiveUnfixedHighOrCritical,1);
  const tampered = structuredClone(receipt);
  tampered.decisions.find(d=>d.state==='fixed_upstream').version='1.21.3';
  assert.throws(()=>effectiveC54Summary(summary,tampered));
});
