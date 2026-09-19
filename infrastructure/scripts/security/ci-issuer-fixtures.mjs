// Ordinary application checks use the already reviewed infrastructure digests.
// A changed infrastructure recipe still receives its separate upgrade rehearsal.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {inventory} from '../../runtime-security/upstream-images.mjs';
const rows=inventory([{path:'docker-compose.yaml',text:await readFile('docker-compose.yaml','utf8')}]);
for(const [service,tag] of [['mysql','otziv-mysql-ci'],['keycloak-postgres','otziv-postgres-ci'],['keycloak','otziv-issuer-ci']]) {
  const selected=rows.filter(row=>row.references.some(ref=>ref.path==='docker-compose.yaml'&&ref.service===service));
  assert.equal(selected.length,1,'Reviewed fixture missing or ambiguous: '+service);
  const reference=selected[0].image;
  assert.match(reference,/^[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}$/);
  execFileSync('docker',['pull','--platform','linux/amd64',reference],{stdio:'inherit'});
  execFileSync('docker',['tag',reference,tag],{stdio:'inherit'});
}
