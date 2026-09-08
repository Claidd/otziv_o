import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readdir,rm,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {gzipSync} from 'node:zlib';
import {requireCaptureMode,requireModeOptions,expectedApplicationSchema,parseRehearsalCli,
  publishedImage,publishedConfig,vpsBaselineApp,vpsCandidateApp,unknownIdentityQueries,
  requireAppendOnlyMigrationHistory,importVpsCapture,rehearse,requireVpsAccountPolicy,requireAccountPolicy,requireVpsBaselineImage,vpsBaselineLocalImage,snapshotPolicy,requireVpsJavaChild,requireOldAppDatabaseFence} from './mysql-upgrade-rehearsal.mjs';
const hash=value=>createHash('sha256').update(value).digest('hex');
const options=()=>({publishedRollback:true,vpsReleaseUpgrade:true,baselineAppImage:vpsBaselineApp,baselineAppCopyReceipt:'private-copy-receipt.json'});
test('old app write fence requires both authoritative server flags and verified release before later writable phases',()=>{
  assert.deepEqual(requireOldAppDatabaseFence('1\t1\n',true),{readOnly:true,superReadOnly:true});
  assert.deepEqual(requireOldAppDatabaseFence('0\t0\n',false),{readOnly:false,superReadOnly:false});
  for(const row of ['0\t0','1\t0','0\t1','ON\tON',''])assert.throws(()=>requireOldAppDatabaseFence(row,true));
  for(const row of ['1\t1','1\t0','0\t1',''])assert.throws(()=>requireOldAppDatabaseFence(row,false));
  assert.throws(()=>requireOldAppDatabaseFence('1\t1','true'));
});
test('controlled old-app shutdown admits only exact copied image and sole direct JVM child',()=>{
  const value={image:vpsBaselineLocalImage,pid1:'sh',children:'7',childComm:'java',childParent:'1',childExecutable:'/opt/java/openjdk/bin/java'};
  assert.equal(requireVpsJavaChild(value).childPid,7);
  for(const delta of [{image:vpsCandidateApp},{pid1:'java'},{children:'7 8'},{children:'1'},{children:''},{children:'7;kill'},{childParent:'2'},{childComm:'python'},{childExecutable:'/tmp/java'}])assert.throws(()=>requireVpsJavaChild({...value,...delta}));
});
test('287 requires original business and queue structures; 310 still requires all new delivery identities',()=>{
  const original=['orders','reviews','leads','common_invoices','payment_links','review_performer_assignments','review_performer_offers','lead_sync_queue'];
  const baseline=snapshotPolicy('1.10.287',original);assert.deepEqual(baseline.checksumTables,original);assert.match(baseline.queueSql,/FROM lead_sync_queue/);assert.doesNotMatch(baseline.queueSql,/lead_command_queue|performer_notification_intents/);
  for(const table of original)assert.throws(()=>snapshotPolicy('1.10.287',original.filter(value=>value!==table)),/representative_tables_missing/);
  const current=[...original.filter(value=>value!=='lead_sync_queue'),'lead_command_queue','performer_notification_intents','order_publication_client_updates','order_client_message_occurrences','client_message_operations','scheduled_client_message_state'];
  assert.match(snapshotPolicy('1.10.310',current).queueSql,/blocking_lead_id/);
  for(const table of current)assert.throws(()=>snapshotPolicy('1.10.310',current.filter(value=>value!==table)),/tables_missing/);
  assert.throws(()=>snapshotPolicy('1.10.286',original),/requires_review/);
});
function records(){const owner='otziv-mysql-vps-release-11111111-2222-3333-4444-555555555555',publishedTarget={reference:publishedImage,configurationDigest:publishedConfig};
  const captureInfo={schema:'otziv-mysql-vps-release-capture-v1',mode:'vps-release-upgrade',owner,publishedTarget,sourceContainer:'my-mysql',sourceProject:'otziv-prod',sourceVolume:'docker_mysql_data',sourceSchemaVersion:'1.10.287',sourceImage:'sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383',baselineAppImage:vpsBaselineApp,candidateAppImage:vpsCandidateApp,originalCaptureSha256:'a'.repeat(64),sourceWrites:false};
  return {captureInfo,claim:{owner,mode:'vps-release-upgrade',publishedTarget}};}
test('actual VPS mode binds287 baseline and310 candidate independently of historical local modes',()=>{
  const r=records();assert.equal(requireCaptureMode(r.captureInfo,r.claim,options()),true);
  assert.equal(expectedApplicationSchema('1.10.287',true,true),'1.10.310');
  assert.throws(()=>expectedApplicationSchema('1.10.287',true),/requires_review/);
  assert.throws(()=>requireCaptureMode(r.captureInfo,r.claim,{publishedRollback:true}),/capture_schema_required/);
  assert.throws(()=>requireCaptureMode(r.captureInfo,r.claim),/legacy_capture_schema_required/);
});
for(const field of ['schema','mode','sourceContainer','sourceProject','sourceVolume','sourceSchemaVersion','sourceImage','baselineAppImage','candidateAppImage','originalCaptureSha256','sourceWrites'])test('actual VPS changed '+field+' cannot reach a Docker operation',()=>{
  const r=records();r.captureInfo[field]='changed';assert.throws(()=>requireCaptureMode(r.captureInfo,r.claim,options()));
});
test('VPS branch rejects unreviewed old app, implicit publication and every historical resume',()=>{
  for(const delta of [{baselineAppImage:vpsCandidateApp},{publishedRollback:false},{resumePreparation:true},{hardenedImage:'anything'},{retainAfterPass:true}])assert.throws(()=>requireModeOptions({...options(),...delta}));
  assert.throws(()=>requireModeOptions({baselineAppImage:vpsBaselineApp}));
});
test('VPS reproduces only its exact four grants without relaxing historical local policy',()=>{
  const grants=['GRANT USAGE ON *.* TO `hunt`@`%`','GRANT ALL PRIVILEGES ON `otziv`.* TO `hunt`@`%`','GRANT ALL PRIVILEGES ON `otziv_archive_check_20260714`.* TO `hunt`@`%`','GRANT ALL PRIVILEGES ON `otziv_migration_check_20260714`.* TO `hunt`@`%`'];
  const account={user:'hunt',host:'%',grants};assert.equal(requireVpsAccountPolicy(account,'otziv').grantCount,4);assert.throws(()=>requireAccountPolicy(account,'otziv'));
  for(const changed of [grants.slice(0,3),[...grants,'GRANT SUPER ON *.* TO `hunt`@`%`'],grants.map(g=>g.replace('archive_check_20260714','another_schema'))])assert.throws(()=>requireVpsAccountPolicy({...account,grants:changed},'otziv'));
});
test('classic VPS config and containerd manifest require exact copy receipt and all RootFS layers',()=>{
  const metadata={Id:vpsBaselineLocalImage,Os:'linux',Architecture:'amd64',RootFS:{Type:'layers',Layers:['sha256:'+'a'.repeat(64),'sha256:'+'b'.repeat(64)]}},source={imageId:vpsBaselineApp,imageMetadata:structuredClone(metadata)};
  const receipt={schema:'otziv-exact-running-vps-app-copy-v1',imageConfigurationDigest:vpsBaselineApp,localImageId:vpsBaselineLocalImage,configurationReexportHashMatches:true,rootFilesystemMatches:true,platformMatches:true,sourceContainerUnchanged:true,sourceWrites:false};
  assert.equal(requireVpsBaselineImage(metadata,source,receipt).localImageId,vpsBaselineLocalImage);
  for(const delta of [{localImageId:vpsBaselineApp},{imageConfigurationDigest:vpsCandidateApp},{configurationReexportHashMatches:false},{rootFilesystemMatches:false},{sourceWrites:true}])assert.throws(()=>requireVpsBaselineImage(metadata,source,{...receipt,...delta}));
  const changed=structuredClone(metadata);changed.RootFS.Layers[1]='sha256:'+'c'.repeat(64);assert.throws(()=>requireVpsBaselineImage(changed,source,receipt),/rootfs_changed/);
});
test('VPS CLI requires explicit published mode/import and admits no FORCE/resume flag',()=>{
  assert.deepEqual(parseRehearsalCli(['import-vps','out','--input-dir','private']),{command:'import-vps',output:'out',options:{inputDirectory:'private'}});
  const parsed=parseRehearsalCli(['rehearse','out','--published-rollback','--vps-release-upgrade','--app-env-file','private.env','--app-image',vpsCandidateApp,'--baseline-app-image',vpsBaselineApp]);
  assert.equal(parsed.options.baselineAppImage,vpsBaselineApp);assert.equal(parsed.options.vpsReleaseUpgrade,true);
  for(const args of [['capture','out','--vps-release-upgrade','--published-rollback'],['rehearse','out','--vps-release-upgrade'],['import-vps','out','--input-dir','private','--force'],['rehearse','out','--published-rollback','--vps-release-upgrade','--resume-app-preparation']])assert.throws(()=>parseRehearsalCli(args));
});
test('migration proof preserves all existing Flyway bytes and requires successful append to310',()=>{
  const before='1\t1.10.287\told\tSQL\told.sql\t11\t1\n',after=before+'2\t1.10.310\tnew\tSQL\tnew.sql\t22\t1\n';
  assert.equal(requireAppendOnlyMigrationHistory(before,after).appendedRows,1);
  for(const changed of [after.replace('11\t1','12\t1'),after.replace('1.10.310','1.10.309'),after.slice(0,-2)+'0\n',before])assert.throws(()=>requireAppendOnlyMigrationHistory(before,changed));
});
test('UNKNOWN query freezes original columns including envelopes and stable PK, ignores added schema columns',()=>{
  const metadata='deliveries\tid\tPRI\ndeliveries\tdelivery_status\t\ndeliveries\tdelivery_token\t\ndeliveries\tdelivery_message\t\norders\tid\tPRI\norders\tvalue\t\n';
  const [query]=unknownIdentityQueries(metadata);assert.match(query,/`id`,`delivery_status`,`delivery_token`,`delivery_message`/);assert.match(query,/'UNKNOWN','LEGACY_UNKNOWN'/);assert.match(query,/ORDER BY `id`/);
  assert.equal(unknownIdentityQueries(metadata).length,1);
  assert.throws(()=>unknownIdentityQueries(metadata.replace('deliveries','evil`; DROP')));
  assert.throws(()=>unknownIdentityQueries('no_key\tstate\t\n'),/stable_primary_key/);
});
test('historical copied snapshots without a PK retain full duplicate row multiset with nontruncated NULL-safe binary ordering',()=>{
  const [query]=unknownIdentityQueries('codex_old_backup\tstatus\t\tvarchar\ncodex_old_backup\tdelivery_message\t\ttext\n');
  assert.match(query,/SELECT 'codex_old_backup',`status`,`delivery_message`/);
  assert.match(query,/ORDER BY SHA2\(CONCAT_WS\('\|','0:status:varchar',IFNULL\(HEX\(CAST\(`status` AS BINARY\)\),'NULL'\),'1:delivery_message:text',IFNULL\(HEX\(CAST\(`delivery_message` AS BINARY\)\),'NULL'\)\),256\),CAST\(CONCAT_WS/);
  assert.doesNotMatch(query,/DISTINCT|GROUP BY/);
  assert.equal(unknownIdentityQueries('_bak_incidents\tstatus\t\tvarchar\n').length,1);
  assert.throws(()=>unknownIdentityQueries('_bak_incidents\tstatus\t\n'),/column_types_required/);
  assert.throws(()=>unknownIdentityQueries('delivery_operations\tstatus\t\n'),/stable_primary_key/);
});
test('wrong app pair rejects before private claim/dump access',async()=>{
  const folder=await mkdtemp(join(tmpdir(),'vps-app-gate-'));
  try{await assert.rejects(rehearse(folder,{...options(),appEnvFile:'missing',appImage:vpsBaselineApp}),/vps_candidate_app_requires_review/);assert.deepEqual(await readdir(folder),[]);}finally{await rm(folder,{recursive:true,force:true});}
});
test('tampered imported VPS dump is rejected before creating an owned run or contacting any source',async()=>{
  const folder=await mkdtemp(join(tmpdir(),'vps-import-gate-')),output=join(folder,'output');
  try{
    const dump=gzipSync('SELECT 1;'),capture={schema:'otziv-actual-vps-readonly-capture-v1',sourceProject:'otziv-prod',sourceDatabaseVersion:'9.0.0',sourceSchemaVersion:'1.10.287',sourceContainer:{name:'my-mysql',id:'a'.repeat(64),imageReference:'mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383',imageId:'sha256:31ebb0b19998d2c1b8bcbb8fc0f0e676dcc9a758b2fea940f8e6aa27b4c916cf',mount:{Name:'docker_mysql_data',Type:'volume',Destination:'/var/lib/mysql'}},sourceApplication:{imageId:vpsBaselineApp},sourceWrites:false,flywayHistoryBeforeEqualsAfter:true,localDownloadVerified:true,temporaryRemoteDumpRemoved:true,database:'otziv',dump:{file:'source.sql.gz',bytes:dump.length,sha256:'a'.repeat(64)}};
    const original=JSON.stringify(capture);await writeFile(join(folder,'capture.json'),original);await writeFile(join(folder,'source.sql.gz'),dump);
    await assert.rejects(importVpsCapture(output,{inputDirectory:folder}),/vps_dump_hash_mismatch/);
    assert.deepEqual((await readdir(folder)).sort(),['capture.json','source.sql.gz']);assert.equal(await readFile(join(folder,'capture.json'),'utf8'),original);assert.equal(hash(await readFile(join(folder,'source.sql.gz'))),hash(dump));
  }finally{await rm(folder,{recursive:true,force:true});}
});
