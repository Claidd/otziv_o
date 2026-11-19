import assert from 'node:assert/strict';
import {hash} from './postgres-c51-adjudication.mjs';
import {c58Receipt, PARENT, PARENT_ID} from './postgres-c58-adjudication.mjs';
import {C48_KEYCLOAK_REFERENCE, C48_KEYCLOAK_CONFIG, C51_REQUIRED_CHECKS,
  validatePostgresRehearsal} from './postgres-c51-activation.mjs';
import {validateC54Transition} from './postgres-c54-activation.mjs';
import {buildTriage} from './triage-report.mjs';
import {combinedScanSummary} from './scan-verdict.mjs';
import {summarizeReport, TRIVY_IMAGE} from './scan.mjs';

const source = 'infrastructure/runtime-security/';
const proof = source+'proofs/c58-postgres/';
const directory = proof+'publication/';
const parentHash = '587c917466a529a0e5dca5ad44bf3c7c4e1526db583c138b30d7d39be2573624';
const transition = {generation:'c58',source:PARENT,sourceConfig:PARENT_ID,sourceVersion:'17.12',targetVersion:'17.12',
  checks:[...C51_REQUIRED_CHECKS,'server_version_unchanged','postgres_indexes_valid']};

export async function validateC58Scan(publication,path,read) {
  assert.deepEqual([publication.component,publication.manifestSet,path],['postgres','c58-postgres',directory+'publication.json']);
  const [rawBytes,receiptBytes,triageBytes,configBytes,runtimeBytes] = await Promise.all([
    'vulnerabilities.json','vulnerabilities.adjudications.json','vulnerabilities.triage.json',
    'registry-amd64-config.json','vulnerabilities.runtime-c58.json.gz'].map(file=>read(directory+file)));
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
  const postgres = await c58Receipt(raw,rawBytes,receipt.postgres.immutableImageId,runtimeBytes,publication.commit);
  assert.deepEqual(receipt.postgres,postgres);
  const security = {...combinedScanSummary(summarizeReport(raw),{grafana:receipt,alloy:receipt.alloy,postgres}),scannerImage:TRIVY_IMAGE};
  assert.deepEqual(publication.security,security);
  assert.deepEqual([security.result,security.effectiveBlockingFixedHighOrCritical,security.unresolvedRiskReview],['PASS',0,'NONE']);
}

export async function validateC58Acceptance(entry,read) {
  const bound = entry.postgresC58Acceptance;
  assert.deepEqual([entry.component,entry.manifest?.path,bound?.path],['postgres',source+'reviewed-images-c58-postgres.json',proof+'acceptance.json']);
  assert.deepEqual(entry.databaseTransition,bound);
  const bytes = await read(bound.path);
  assert.equal(hash(bytes),bound.sha256);
  const accepted = JSON.parse(bytes);
  assert.deepEqual([accepted.schema,accepted.result,accepted.parentReference,accepted.parentConfigId],
    ['otziv-postgres-c58-acceptance-v1','PASS',PARENT,PARENT_ID]);
  const publication = JSON.parse(await read(entry.publication.path));
  for (const key of ['reference','commit','run','attempt']) {
    assert.equal(accepted[key],entry[key]);
    assert.equal(accepted[key],publication[key]);
  }
  assert.equal(accepted.imageConfigId,publication.imageId);
  assert.deepEqual(accepted.pair,{postgres:{reference:entry.reference,imageConfigId:publication.imageId},
    keycloak:{reference:C48_KEYCLOAK_REFERENCE,imageConfigId:C48_KEYCLOAK_CONFIG}});
  assert.equal(accepted.runtimePath,directory+'vulnerabilities.runtime-c58.json.gz');
  for (const [key,file] of Object.entries({capturePath:'capture',replayPath:'replay',captureWindowPath:'capture-window'}))
    assert.equal(accepted[key],proof+'rehearsal/'+file+'.json');
  for (const path of [entry.publication.path,accepted.runtimePath,accepted.capturePath,accepted.replayPath,accepted.captureWindowPath,
    ...['vulnerabilities.json','vulnerabilities.adjudications.json','vulnerabilities.triage.json','registry-amd64-config.json'].map(n=>directory+n)])
    assert.ok(accepted.files?.[path],path);
  const executed = ['postgres-c58-inspection.py','ssl-refresh-inspection.py','postgres-c58-adjudication.mjs','postgres-c58-rehearsal.py',
    'postgres-c58-capture-window.py','builds/c58-postgres/Dockerfile'].map(p=>source+p);
  for (const path of executed) assert.ok(accepted.executedSources?.[path],path);
  for (const [path,sha] of Object.entries({...accepted.files,...accepted.executedSources})) {
    assert.ok(path.startsWith(source) && !/[\\:]/.test(path) && path.split('/').every(p=>p && p!=='.' && p!=='..'));
    assert.equal(hash(await read(path)),sha,'postgres_c58_evidence_changed:'+path);
  }
  await validateC58Scan(publication,entry.publication.path,read);
  await validatePostgresRehearsal(accepted,read,transition);
  return accepted;
}

export async function validateC58Transition(entry,image,read) {
  const accepted = await validateC58Acceptance(entry,read);
  const bytes = await read(source+'c58-parent-postgres.json');
  assert.equal(hash(bytes),parentHash);
  const parent = await validateC54Transition(JSON.parse(bytes),image,read);
  assert.equal(parent.postgresReference,PARENT);
  assert.equal(parent.postgresConfigId,PARENT_ID);
  // The very same C48 issuer is exercised by both transitions.
  return {...parent,postgresReference:entry.reference,postgresConfigId:accepted.imageConfigId,
    postgresC58Acceptance:entry.postgresC58Acceptance,ordinaryDeploymentUpgradeAuthorized:false};
}
