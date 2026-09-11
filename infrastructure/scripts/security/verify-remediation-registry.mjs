import {readFile,access} from 'node:fs/promises';
import {resolve,relative,isAbsolute} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

export function validateRegistry(registry) {
  assert.equal(registry.schema,'otziv-architecture-finding-registry-v1');
  assert.equal(registry.findings.length,20,'Every F01–F20 finding needs its own record');
  const ids=new Set();
  for(const finding of registry.findings) {
    assert.match(finding.id,/^F(0[1-9]|1[0-9]|20)$/);assert.ok(!ids.has(finding.id));ids.add(finding.id);
    assert.ok(finding.packages.length&&finding.packages.every(p=>/^P(0[0-9]|1[0-9]|2[01])$/.test(p)));
    assert.ok(finding.implementationOwner&&finding.releaseOwnerRole&&finding.status);
    assert.ok(Array.isArray(finding.migrations)&&finding.evidence.length);
    assert.ok(Array.isArray(finding.remainingAcceptance));
    if(finding.pullRequest===null)assert.ok(finding.pullRequestMissingReason);
    else assert.match(finding.pullRequest,/^https:\/\/github\.com\/Claidd\/otziv_o\/pull\/\d+$/);
    assert.equal(typeof finding.productionReleased,'boolean');
    if(finding.status==='CLOSED') {
      assert.ok(finding.pullRequest&&finding.releaseOwner&&finding.productionReleased,'Closure requires real PR, named release owner and completed rollout');
      assert.equal(finding.remainingAcceptance.length,0,'Open acceptance cannot be labelled closed');
      assert.ok(finding.acceptanceEvidence?.length,'Closure needs release acceptance evidence');
    }
  }
  return registry.findings.flatMap(f=>[...f.migrations,...f.evidence]);
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  const root=resolve(fileURLToPath(new URL('../../../',import.meta.url)));
  const file=resolve(root,process.argv[2]||'docs/ARCHITECTURE_FINDING_REGISTRY_2026-09-07.json');
  const paths=validateRegistry(JSON.parse(await readFile(file,'utf8')));
  for(const path of new Set(paths)) {
    assert.ok(typeof path==='string'&&!isAbsolute(path),'Evidence must be repository-relative');
    const target=resolve(root,path),rel=relative(root,target);
    assert.ok(rel&&!rel.startsWith('..')&&!isAbsolute(rel),'Evidence must stay in the repository');
    await access(target);
  }
  console.log('F01–F20 registry: all records, migration/evidence paths and truthful closure constraints verified.');
}
