import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readdir,rm,realpath} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {requireUpgradeCheck,requireGracefulAppStop,requirePublishedIdentity,requireAccountPolicy,publishedImage,publishedConfig,requireModeOptions,requireCaptureMode,parseRehearsalCli,capture,rehearse,requireSourceSemanticConfiguration,expectedApplicationSchema,nativeOptionFileCommand} from './mysql-upgrade-rehearsal.mjs';

test('Windows-mounted option file becomes a native read-only file before the exact original entrypoint executes',()=>{
  const command=nativeOptionFileCommand(['/entrypoint.sh'],['mysqld','--defaults-file=/fixture/my.cnf']);
  assert.equal(command[0],'-c');assert.ok(command[1].includes('chmod 0644'));assert.ok(command[1].endsWith('exec "$@"'));
  assert.deepEqual(command.slice(3),['/entrypoint.sh','mysqld','--defaults-file=/tmp/otziv-my.cnf']);
  assert.throws(()=>nativeOptionFileCommand([],['mysqld','--defaults-file=/fixture/my.cnf']));
  assert.throws(()=>nativeOptionFileCommand(['/entrypoint.sh'],['mysqld','--upgrade=FORCE']));
});

const sourceSemantic=()=>({character_set_server:'utf8mb4',collation_server:'utf8mb4_unicode_ci',lower_case_table_names:'0',restrict_fk_on_non_standard_key:'OFF',sql_mode:'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION',time_zone:'+08:00'});
test('published capture preserves reviewed timezone/collation/SQL semantics without widening grants or relaxing checks',()=>{
  assert.deepEqual(requireSourceSemanticConfiguration(sourceSemantic()),sourceSemantic());
  for(const delta of [{time_zone:'SYSTEM'},{time_zone:'+08:00\nevent_scheduler=ON'},{collation_server:'latin1_swedish_ci'},{sql_mode:''},{lower_case_table_names:'1'},{restrict_fk_on_non_standard_key:'ON'},{unknown:'setting'}])
    assert.throws(()=>requireSourceSemanticConfiguration({...sourceSemantic(),...delta}));
  const incomplete=sourceSemantic();delete incomplete.sql_mode;assert.throws(()=>requireSourceSemanticConfiguration(incomplete));
});
test('current published rehearsal binds its captured schema and retains the legacy migration target',()=>{
  assert.equal(expectedApplicationSchema('1.10.310',true),'1.10.310');
  assert.equal(expectedApplicationSchema('1.10.306',true),'1.10.306');
  assert.equal(expectedApplicationSchema('1.10.300',false),'1.10.306');
  for(const version of [undefined,'1.10.300','1.10.309','1.10.999'])assert.throws(()=>expectedApplicationSchema(version,true));
});

const complete=()=>({serverVersion:'9.0.0',targetVersion:'9.7.3',errorCount:0,warningCount:2,noticeCount:1,
  checksPerformed:[{id:'syntax',status:'OK'},{id:'reservedKeywords',status:'OK'}],manualChecks:[{title:'Review configuration'}]});
test('completed checker output retains warnings and manual work without inventing zero risk',()=>{
  assert.deepEqual(requireUpgradeCheck(complete()),{errors:0,warnings:2,notices:1,completedChecks:2,manualChecks:[{title:'Review configuration'}]});
});
for(const [name,change] of [
  ['wrong source',value=>{value.serverVersion='8.4.0';}],
  ['wrong target',value=>{value.targetVersion='9.7.0';}],
  ['reported compatibility errors',value=>{value.errorCount=1;}],
  ['missing error count',value=>{delete value.errorCount;}],
  ['empty checks',value=>{value.checksPerformed=[];}],
  ['failed check despite zero aggregate errors',value=>{value.checksPerformed[0].status='ERROR';}],
  ['incomplete check',value=>{value.checksPerformed[0].status='SKIPPED';}]
])test(name+' prevents the in-place upgrade gate from passing',()=>{
  const value=complete();change(value);assert.throws(()=>requireUpgradeCheck(value));
});

const gracefulLog='Graceful shutdown complete\nClosing JPA EntityManagerFactory\nHikariDataSource - primary - Shutdown initiated...\nHikariDataSource - primary - Shutdown completed.';
const stopped=()=>({pid1:'java',exitCode:143,oomKilled:false,elapsedMs:14000});
test('SIGTERM exit143 requires actual completed Spring, JPA and pool shutdown',()=>{
  assert.deepEqual(requireGracefulAppStop(stopped(),gracefulLog,120000),{springComplete:true,entityManagerClosed:true,poolsClosed:1});
});
test('forced kill, shell PID1, OOM and expired deadline each fail closed',()=>{
  for(const delta of [{exitCode:137},{pid1:'sh'},{oomKilled:true},{elapsedMs:120000}])assert.throws(()=>requireGracefulAppStop({...stopped(),...delta},gracefulLog,120000));
});
test('missing shutdown evidence or a second unfinished pool cannot pass',()=>{
  for(const log of ['',gracefulLog.replace('Graceful shutdown complete',''),gracefulLog.replace('Closing JPA EntityManagerFactory',''),gracefulLog+'\nHikariDataSource - secondary - Shutdown initiated...'])assert.throws(()=>requireGracefulAppStop(stopped(),log,120000));
});

test('published C7 identity accepts the pinned manifest and its exact configuration only',()=>{
  for(const Id of [publishedConfig,publishedImage.split('@')[1]])assert.equal(requirePublishedIdentity({Id,RepoDigests:[publishedImage],Os:'linux',Architecture:'amd64'}),Id);
  for(const delta of [{Id:'sha256:'+'0'.repeat(64)},{RepoDigests:[]},{Os:'windows'},{Architecture:'arm64'}])
    assert.throws(()=>requirePublishedIdentity({Id:publishedConfig,RepoDigests:[publishedImage],Os:'linux',Architecture:'amd64',...delta}));
});
test('source application grant capture permits only the reviewed exact database account policy',()=>{
  const account={user:'local_app',host:'%',grants:['GRANT USAGE ON *.* TO `local_app`@`%`','GRANT ALL PRIVILEGES ON `fixture`.* TO `local_app`@`%`']};
  assert.equal(requireAccountPolicy(account,'fixture').grantCount,2);
  for(const delta of [{user:'root'},{user:'bad\'; DROP TABLE x; --'},{host:'localhost'},
    {grants:[...account.grants,'GRANT SUPER ON *.* TO `local_app`@`%`']},
    {grants:[account.grants[0],'GRANT ALL PRIVILEGES ON `another`.* TO `local_app`@`%`']}])
    assert.throws(()=>requireAccountPolicy({...account,...delta},'fixture'));
});

const uuid='11111111-2222-3333-4444-555555555555';
const legacyRecords=()=>({captureInfo:{schema:'otziv-mysql-upgrade-capture-v1',owner:'otziv-mysql-upgrade-'+uuid},claim:{owner:'otziv-mysql-upgrade-'+uuid}});
const publishedRecords=()=>{
  const common={owner:'otziv-mysql-c7-rollback-'+uuid,mode:'published-rollback',publishedTarget:{reference:publishedImage,configurationDigest:publishedConfig}};
  return {captureInfo:{...structuredClone(common),schema:'otziv-mysql-published-rollback-capture-v1',sourceContainer:'otziv-prod-local-mysql-1'},claim:structuredClone(common)};
};
test('legacy V1 capture and old resume/hardened options retain their default route',()=>{
  const {captureInfo,claim}=legacyRecords();
  for(const options of [{},{resumePreparation:true},{resumeViewDefiner:true},{prepareViewDefiner:true},{hardenedImage:'fixture:reviewed',retainAfterPass:true}]) {
    assert.equal(requireCaptureMode(captureInfo,claim,options),false);
  }
  assert.deepEqual(parseRehearsalCli(['capture','fixture','--source-ready']),{command:'capture',output:'fixture',options:{sourceReady:true,publishedRollback:false}});
  const old=parseRehearsalCli(['rehearse','fixture','--app-env-file','private.env','--app-image','app:exact','--resume-app-preparation','--resume-view-definer-preparation','--prepare-reviewed-view-definer','--hardened-image','db:exact','--retain-after-pass']);
  assert.deepEqual(old.options,{appEnvFile:'private.env',appImage:'app:exact',resumePreparation:true,resumeViewDefiner:true,prepareViewDefiner:true,hardenedImage:'db:exact',retainAfterPass:true,publishedRollback:false});
});
test('published capture and rehearsal require explicit mode and matching immutable records',()=>{
  const {captureInfo,claim}=publishedRecords();
  assert.equal(requireCaptureMode(captureInfo,claim,{publishedRollback:true}),true);
  assert.equal(parseRehearsalCli(['capture','fixture','--source-ready','--published-rollback']).options.publishedRollback,true);
  const cli=parseRehearsalCli(['rehearse','fixture','--published-rollback','--app-env-file','private.env','--app-image','app:exact']);
  assert.equal(requireCaptureMode(captureInfo,claim,cli.options),true);
  assert.equal(cli.options.appImage,'app:exact');
});
test('explicit flag cannot promote a legacy or historical unbound C7 capture',()=>{
  const {captureInfo,claim}=legacyRecords();
  assert.throws(()=>requireCaptureMode(captureInfo,claim,{publishedRollback:true}),/published_capture_schema_required/);
  const oldProof={...captureInfo,owner:'otziv-mysql-c7-rollback-'+uuid,accountPolicy:{grantCount:2}};
  assert.throws(()=>requireCaptureMode(oldProof,{owner:oldProof.owner},{publishedRollback:true}),/published_capture_schema_required/);
});
test('omitted flag cannot silently run a published capture through the generic upgrade',()=>{
  const {captureInfo,claim}=publishedRecords();
  assert.throws(()=>requireCaptureMode(captureInfo,claim),/legacy_capture_schema_required/);
  captureInfo.schema='otziv-mysql-upgrade-capture-v1';
  assert.throws(()=>requireCaptureMode(captureInfo,claim),/explicit_published_mode_required/);
});
for(const field of ['schema','mode','owner','publishedTarget','sourceContainer'])test('changed published capture '+field+' rejects before execution',()=>{
  const {captureInfo,claim}=publishedRecords();
  captureInfo[field]=field==='publishedTarget'?{reference:publishedImage,configurationDigest:'sha256:'+'0'.repeat(64)}:'changed';
  assert.throws(()=>requireCaptureMode(captureInfo,claim,{publishedRollback:true}));
});
for(const field of ['mode','owner','publishedTarget'])test('changed owner '+field+' rejects before execution',()=>{
  const {captureInfo,claim}=publishedRecords();delete claim[field];
  assert.throws(()=>requireCaptureMode(captureInfo,claim,{publishedRollback:true}));
});
for(const [option,value] of [['resumePreparation',true],['resumeViewDefiner',true],['prepareViewDefiner',true],['hardenedImage','db:other'],['retainAfterPass',true]])test('published mode refuses '+option+' mixed branch',()=>{
  assert.throws(()=>requireModeOptions({publishedRollback:true,[option]:value}),/published_rollback_requires_new_complete_run/);
});
test('published CLI rejects unsupported, duplicate, missing-value and mixed flags',()=>{
  for(const flags of [
    ['--retain-after-pass'],['--resume-app-preparation'],['--resume-view-definer-preparation'],['--prepare-reviewed-view-definer'],
    ['--hardened-image','db:other'],['--source-ready'],['--unexpected'],['--published-rollback'],
    ['--app-env-file'],['--app-env-file','--app-image','app:exact'],['--app-image','one','--app-image','two'],['stray-value'],
  ])assert.throws(()=>parseRehearsalCli(['rehearse','fixture','--published-rollback',...flags]),/published_rollback_/);
  for(const flags of [['--app-image','app:exact'],['--source-ready','--source-ready'],['--unexpected']])
    assert.throws(()=>parseRehearsalCli(['capture','fixture','--published-rollback',...flags]),/published_rollback_/);
  for(const value of ['true',1,null])assert.throws(()=>requireModeOptions({publishedRollback:value}),/published_mode_must_be_boolean/);
});
test('capture still requires explicit source-ready before contacting Docker',async()=>{
  await assert.rejects(capture('uncreated-fixture',{publishedRollback:true}),/source_ready_write_fence_confirmation_required/);
  await assert.rejects(capture('uncreated-fixture'),/source_ready_write_fence_confirmation_required/);
});
test('actual rehearsal rejects mode mismatch before dump read, claim write or Docker operation',async()=>{
  const out=await realpath(await mkdtemp(join(tmpdir(),'mysql-mode-')));
  try {
    for(const [records,options,error] of [[legacyRecords(),{publishedRollback:true},/published_capture_schema_required/],[publishedRecords(),{},/legacy_capture_schema_required/]]) {
      await writeFile(join(out,'capture.json'),JSON.stringify(records.captureInfo));
      await writeFile(join(out,'owner.json'),JSON.stringify({...records.claim,output:out}));
      await assert.rejects(rehearse(out,{appEnvFile:'nonexistent-private.env',...options}),error);
      assert.deepEqual((await readdir(out)).sort(),['capture.json','owner.json']);
    }
    await assert.rejects(rehearse(out,{appEnvFile:'nonexistent-private.env',publishedRollback:true,prepareViewDefiner:true}),/published_rollback_requires_new_complete_run/);
  } finally {await rm(out,{recursive:true,force:true});}
});
