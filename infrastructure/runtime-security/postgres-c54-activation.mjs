import assert from 'node:assert/strict';
import {hash} from './postgres-c51-adjudication.mjs';
import {c54Receipt, PARENT, PARENT_ID} from './postgres-c54-adjudication.mjs';
import {C48_KEYCLOAK_REFERENCE, C48_KEYCLOAK_CONFIG, C51_REQUIRED_CHECKS,
  validatePostgresRehearsal, validatePostgresC51Transition} from './postgres-c51-activation.mjs';
import {buildTriage} from './triage-report.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {summarizeReport, TRIVY_IMAGE} from './scan.mjs';

const source = 'infrastructure/runtime-security/';
const proof = source+'proofs/c54-postgres/';
const directory = proof+'publication/';
const parentHash = 'aac6a5b31229098b7151a1f0b6bb2d6a46e8a18034a20d6645e5a59b2e34f954';
const transition = {generation:'c54',source:PARENT,sourceConfig:PARENT_ID,targetVersion:'17.12',
  checks:[...C51_REQUIRED_CHECKS,'server_version_upgrade','postgres_indexes_valid']};

export async function validateC54Scan(publication,path,read) {
  assert.deepEqual([publication.component,publication.manifestSet,path],['postgres','c54-postgres',directory+'publication.json']);
  const [rawBytes,receiptBytes,triageBytes,configBytes,runtimeBytes] = await Promise.all([
    'vulnerabilities.json','vulnerabilities.adjudications.json','vulnerabilities.triage.json',
    'registry-amd64-config.json','vulnerabilities.runtime-c54.json.gz'].map(file=>read(directory+file)));
  const config = JSON.parse(configBytes), raw = JSON.parse(rawBytes), receipt = JSON.parse(receiptBytes);
  assert.equal('sha256:'+hash(configBytes),publication.imageId);
  assert.equal(raw.Metadata.ImageID,publication.imageId);
  assert.deepEqual(raw.Metadata.ImageConfig.rootfs,config.rootfs);
  assert.equal(raw.Metadata.ImageConfig.config.Labels['com.otziv.publication.revision'],publication.commit);
  assert.deepEqual(JSON.parse(triageBytes),buildTriage(raw,rawBytes));
  for (const other of [receipt,receipt.alloy]) {
    assert.equal(other.status,'NOT_APPLICABLE');
    assert.deepEqual(other.decisions,[]);
    assert.equal(other.rawReportSha256,hash(rawBytes));
    assert.equal(other.imageConfigId,publication.imageId);
    assert.equal(other.rawReportModified,false);
  }
  assert.equal(receipt.gosu,undefined);
  assert.ok([publication.imageId,publication.reference.split('@')[1]].includes(receipt.postgres?.immutableImageId));
  const postgres = await c54Receipt(raw,rawBytes,receipt.postgres.immutableImageId,runtimeBytes,publication.commit);
  assert.deepEqual(receipt.postgres,postgres);
  const security = {...combinedScanSummary(summarizeReport(raw),{grafana:receipt,alloy:receipt.alloy,postgres}),scannerImage:TRIVY_IMAGE};
  assert.deepEqual(publication.security,security);
  assert.deepEqual([security.result,security.effectiveBlockingFixedHighOrCritical,security.unresolvedRiskReview],['PASS',0,'NONE']);
}

export async function validateC54Acceptance(entry,read) {
  const bound = entry.postgresC54Acceptance;
  assert.deepEqual([entry.component,entry.manifest?.path,bound?.path],['postgres',source+'reviewed-images-c54-postgres.json',proof+'acceptance.json']);
  assert.deepEqual(entry.databaseTransition,bound);
  const bytes = await read(bound.path);
  assert.equal(hash(bytes),bound.sha256);
  const accepted = JSON.parse(bytes);
  assert.deepEqual([accepted.schema,accepted.result,accepted.parentReference,accepted.parentConfigId],
    ['otziv-postgres-c54-acceptance-v1','PASS',PARENT,PARENT_ID]);
  const publication = JSON.parse(await read(entry.publication.path));
  for (const key of ['reference','commit','run','attempt']) {
    assert.equal(accepted[key],entry[key]);
    assert.equal(accepted[key],publication[key]);
  }
  assert.equal(accepted.imageConfigId,publication.imageId);
  assert.deepEqual(accepted.pair,{postgres:{reference:entry.reference,imageConfigId:publication.imageId},
    keycloak:{reference:C48_KEYCLOAK_REFERENCE,imageConfigId:C48_KEYCLOAK_CONFIG}});
  assert.equal(accepted.runtimePath,directory+'vulnerabilities.runtime-c54.json.gz');
  for (const [key,file] of Object.entries({capturePath:'capture',replayPath:'replay',captureWindowPath:'capture-window'}))
    assert.equal(accepted[key],proof+'rehearsal/'+file+'.json');
  for (const path of [entry.publication.path,accepted.runtimePath,accepted.capturePath,accepted.replayPath,accepted.captureWindowPath,
    ...['vulnerabilities.json','vulnerabilities.adjudications.json','vulnerabilities.triage.json','registry-amd64-config.json'].map(n=>directory+n)])
    assert.ok(accepted.files?.[path],path);
  const executed = ['postgres-c54-inspection.py','ssl-refresh-inspection.py','postgres-c54-adjudication.mjs','postgres-c54-rehearsal.py',
    'postgres-c54-capture-window.py',...['Dockerfile','build.sh','package.py','kerberos.py'].map(p=>'builds/postgres-17.12/'+p)].map(p=>source+p);
  for (const path of executed) assert.ok(accepted.executedSources?.[path],path);
  for (const [path,sha] of Object.entries({...accepted.files,...accepted.executedSources})) {
    assert.ok(path.startsWith(source) && !/[\\:]/.test(path) && path.split('/').every(p=>p && p!=='.' && p!=='..'));
    assert.equal(hash(await read(path)),sha,'postgres_c54_evidence_changed:'+path);
  }
  await validateC54Scan(publication,entry.publication.path,read);
  await validatePostgresRehearsal(accepted,read,transition);
  return accepted;
}

export async function validateC54Transition(entry,image,read) {
  const accepted = await validateC54Acceptance(entry,read);
  const bytes = await read(source+'c54-parent-postgres.json');
  assert.equal(hash(bytes),parentHash);
  const parent = await validatePostgresC51Transition(JSON.parse(bytes),image,read);
  assert.equal(parent.postgresReference,PARENT);
  assert.equal(parent.postgresConfigId,PARENT_ID);
  // The very same C48 issuer is exercised by both transitions.
  return {...parent,postgresReference:entry.reference,postgresConfigId:accepted.imageConfigId,
    postgresC54Acceptance:entry.postgresC54Acceptance,ordinaryDeploymentUpgradeAuthorized:false};
}
