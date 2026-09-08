import assert from 'node:assert/strict';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {mkdir,readFile,writeFile,stat,unlink,realpath} from 'node:fs/promises';
import {createReadStream,createWriteStream} from 'node:fs';
import {createGzip,createGunzip} from 'node:zlib';
import {pipeline} from 'node:stream/promises';
import {Readable} from 'node:stream';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {run,startProcess} from '../recovery/process.mjs';
import {dockerEnvironment} from '../recovery/docker-environment.mjs';

const sourceContainer='otziv-prod-local-mysql-1';
const sourceClient='MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names';
const sourceDump='MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --user="$MYSQL_USER" --single-transaction --quick --routines --triggers --events --no-tablespaces --hex-blob --set-gtid-purged=OFF "$MYSQL_DATABASE"';
const input=value=>Readable.from([value]);
const digest=value=>createHash('sha256').update(value).digest('hex');
export const targetImage='container-registry.oracle.com/mysql/community-server@sha256:bf955135c21e2c1c153417407fbbbb8efa786f487781b1fb6baaf7aba17d4340';
const targetConfig='sha256:3fa754b144aeb7be887e6c614522aa06a2bbcd8ac2f3b67414884fa79000cdab';
const label='com.otziv.mysql-upgrade.owner';
const ownerPrefix='otziv-mysql-c7-rollback-';
export const publishedImage='ghcr.io/claidd/otziv-security@sha256:3a3caaab4e71b3bfdec9da21c17c00ed10ca237151919aeddac8e5ce4b8b7baa';
export const publishedConfig='sha256:80ee3b50147a329addbaf754abc4dce86dc06636624680d780351e2320a11070';
export function requirePublishedIdentity(metadata) {
  assert.ok(metadata.RepoDigests?.includes(publishedImage),'published_repository_digest_mismatch');
  assert.equal(metadata.Os,'linux','published_os_mismatch');
  assert.equal(metadata.Architecture,'amd64','published_architecture_mismatch');
  assert.ok([publishedConfig,publishedImage.split('@')[1]].includes(metadata.Id),'published_local_identity_mismatch');
  return metadata.Id;
}
export function requireAccountPolicy(account,database) {
  assert.match(database,/^[a-zA-Z0-9_]+$/,'source_schema_invalid');
  assert.match(account.user,/^[a-zA-Z0-9_]+$/,'source_account_invalid');
  assert.ok(!['root','fixture'].includes(account.user),'source_account_not_application_user');
  assert.equal(account.host,'%','source_account_host_requires_review');
  const target='`'+account.user+'`@`%`';
  assert.deepEqual([...account.grants].sort(),[
    'GRANT USAGE ON *.* TO '+target,
    'GRANT ALL PRIVILEGES ON `'+database+'`.* TO '+target,
  ].sort(),'source_account_grants_require_review');
  return {grantCount:2,scope:'EXACT_DATABASE_ALL_PRIVILEGES_WITH_GLOBAL_USAGE_ONLY'};
}
const publishedMode='published-rollback';
const publishedCaptureSchema='otziv-mysql-published-rollback-capture-v1';
const publishedTarget=()=>({reference:publishedImage,configurationDigest:publishedConfig});

export function requireModeOptions({publishedRollback=false,resumePreparation=false,resumeViewDefiner=false,prepareViewDefiner=false,hardenedImage,retainAfterPass=false}={}) {
  assert.equal(typeof publishedRollback,'boolean','published_mode_must_be_boolean');
  if(publishedRollback)assert.ok(!resumePreparation&&!resumeViewDefiner&&!prepareViewDefiner&&!hardenedImage&&!retainAfterPass,'published_rollback_requires_new_complete_run');
  return publishedRollback;
}

// Explicit CLI intent and both capture records must agree before opening a dump,
// claiming a run, or contacting Docker. Historical generic captures remain V1.
export function requireCaptureMode(captureInfo,claim,options={}) {
  const publishedRollback=requireModeOptions(options);
  assert.equal(claim.owner,captureInfo.owner,'capture_owner_mismatch');
  if(publishedRollback) {
    assert.equal(captureInfo.schema,publishedCaptureSchema,'published_capture_schema_required');
    assert.equal(captureInfo.mode,publishedMode,'capture_mode_mismatch');
    assert.equal(claim.mode,publishedMode,'owner_mode_mismatch');
    assert.deepEqual(captureInfo.publishedTarget,publishedTarget(),'captured_published_identity_mismatch');
    assert.deepEqual(claim.publishedTarget,publishedTarget(),'owner_published_identity_mismatch');
    assert.equal(captureInfo.sourceContainer,sourceContainer,'capture_source_container_mismatch');
    assert.match(claim.owner,/^otziv-mysql-c7-rollback-[a-f0-9-]{36}$/,'capture_owner_invalid');
  } else {
    assert.equal(captureInfo.schema,'otziv-mysql-upgrade-capture-v1','legacy_capture_schema_required');
    for(const record of [captureInfo,claim]) {
      assert.equal(record.mode,undefined,'explicit_published_mode_required');
      assert.equal(record.publishedTarget,undefined,'explicit_published_identity_required');
    }
    assert.match(claim.owner,/^otziv-mysql-upgrade-[a-f0-9-]{36}$/,'capture_owner_invalid');
  }
  return publishedRollback;
}

const pause=ms=>new Promise(done=>setTimeout(done,ms));
async function fileDigest(path) {const hash=createHash('sha256');for await(const part of createReadStream(path))hash.update(part);return hash.digest('hex');}
async function privateDirectory(path) {
  await mkdir(path,{mode:0o700});
  if(process.platform==='win32') {
    const identity=await run('whoami',['/user','/fo','csv','/nh']);
    const sid=identity.match(/S-1-[0-9-]+/)?.[0];assert.ok(sid,'local_owner_sid_missing');
    await run('icacls',[path,'/inheritance:r','/grant:r','*'+sid+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F']);
  }
}
export async function capture(output,{sourceReady=false,publishedRollback=false}={}) {
  requireModeOptions({publishedRollback});
  assert.ok(sourceReady,'source_ready_write_fence_confirmation_required');
  const context=(await run('docker',['context','inspect','--format','{{.Endpoints.docker.Host}}'])).trim();
  assert.match(context,/^(npipe|unix):\/\//,'local_docker_required');
  const out=resolve(output),owner=(publishedRollback?ownerPrefix:'otziv-mysql-upgrade-')+randomUUID();
  const modeMetadata=publishedRollback?{mode:publishedMode,publishedTarget:publishedTarget()}:{};
  await privateDirectory(out);await writeFile(join(out,'owner.json'),JSON.stringify({owner,output:out,...modeMetadata}),{flag:'wx',mode:0o600});
  const sourceSql=sql=>run('docker',['exec','-i',sourceContainer,'sh','-c',sourceClient],{input:input(sql),maxOutput:8*1024*1024});
  const sourceImage=(await run('docker',['inspect','--format','{{.Image}}',sourceContainer])).trim();
  if(publishedRollback) {
    const sourceMeta=JSON.parse(await run('docker',['container','inspect','--format','{{json .}}',sourceContainer],{maxOutput:1024*1024}));
    assert.equal(sourceMeta.Config.Labels['com.docker.compose.project'],'otziv-prod-local','source_project_mismatch');
    assert.equal(sourceMeta.Config.Labels['com.docker.compose.service'],'mysql','source_service_mismatch');
    assert.equal(sourceMeta.Mounts.find(m=>m.Destination==='/var/lib/mysql')?.Name,'otziv-prod-local_mysql_data','source_volume_mismatch');
  }
  const startedAt=new Date().toISOString();
  const identity=(await sourceSql('SELECT VERSION(),DATABASE();')).trim().split('\t');assert.match(identity[0],/^9\.0\./);
  let accountMetadata={};
  if(publishedRollback) {
    const accountRows=(await sourceSql('SELECT CURRENT_USER(); SHOW GRANTS FOR CURRENT_USER();')).trim().split('\n');
    const [user,host]=accountRows.shift().split('@'),account={user,host,grants:accountRows};
    accountMetadata={accountPolicy:requireAccountPolicy(account,identity[1]),accountPolicySha256:digest(JSON.stringify(account))};
    await writeFile(join(out,'source-account-grants.json'),JSON.stringify(account,null,2)+'\n',{mode:0o600});
  }
  const history=await sourceSql('SELECT installed_rank,version,description,type,script,COALESCE(checksum,0),success FROM flyway_schema_history ORDER BY installed_rank;');
  const dump=join(out,'source.sql.gz');
  const child=startProcess('docker',['exec',sourceContainer,'sh','-c',sourceDump],{timeoutMs:600000});
  child.child.stdin.end();
  const results=await Promise.allSettled([pipeline(child.child.stdout,createGzip({level:1}),createWriteStream(dump,{flags:'wx',mode:0o600})),child.completed]);
  assert.ok(results.every(result=>result.status==='fulfilled'),'source_dump_failed');
  if(publishedRollback) {
    assert.equal(await sourceSql('SELECT installed_rank,version,description,type,script,COALESCE(checksum,0),success FROM flyway_schema_history ORDER BY installed_rank;'),history,'source_flyway_changed_during_capture');
  }
  const bytes=(await stat(dump)).size;assert.ok(bytes>100,'source_dump_empty');
  const evidence={schema:publishedRollback?publishedCaptureSchema:'otziv-mysql-upgrade-capture-v1',...modeMetadata,...accountMetadata,owner,startedAt,completedAt:new Date().toISOString(),sourceContainer,sourceImage,
    sourceVersion:identity[0],sourceSchemaVersion:history.trim().split('\n').at(-1).split('\t')[1],database:identity[1],dump:{file:'source.sql.gz',bytes,sha256:await fileDigest(dump)},
    flywayHistorySha256:digest(history),flywayRows:history.trim().split('\n').length,
    sourceWrites:false,sourceCaptureMode:'mysqldump single-transaction quick routines triggers events hex-blob, binary gzip pipe',
    consistentDdlFence:'OPERATOR_SOURCE_READY_NO_CONCURRENT_DDL',protectedDirectory:process.platform==='win32'?'OWNER_AND_SYSTEM_ACL':'MODE_0700'};
  await writeFile(join(out,'source-flyway-history.tsv'),history,{mode:0o600});
  await writeFile(join(out,'capture.json'),JSON.stringify(evidence,null,2)+'\n',{mode:0o600});
  console.log(JSON.stringify({result:'DUMP_CAPTURED',output:out,sourceVersion:evidence.sourceVersion,bytes,sha256:evidence.dump.sha256}));
  return evidence;
}

export function requireUpgradeCheck(report) {
  if(!/^9\.0\./.test(report.serverVersion||''))throw Error('checker_source_version_mismatch');
  if(report.targetVersion!=='9.7.3')throw Error('checker_target_version_mismatch');
  if(report.errorCount!==0)throw Error('upgrade_checker_errors');
  if(!Array.isArray(report.checksPerformed)||!report.checksPerformed.length)throw Error('upgrade_checker_empty');
  if(!report.checksPerformed.every(check=>check.status==='OK'))throw Error('upgrade_checker_incomplete');
  return {errors:report.errorCount,warnings:report.warningCount,notices:report.noticeCount,
    completedChecks:report.checksPerformed.length,manualChecks:report.manualChecks||[]};
}

export function requireGracefulAppStop({pid1,exitCode,oomKilled,elapsedMs},log,deadlineMs) {
  assert.ok(pid1==='java'&&[0,143].includes(exitCode)&&!oomKilled&&elapsedMs<deadlineMs,'app_shutdown_not_graceful');
  assert.ok(log.includes('Graceful shutdown complete'),'spring_shutdown_incomplete');
  assert.ok(log.includes('Closing JPA EntityManagerFactory'),'entity_manager_shutdown_incomplete');
  const pools=[...log.matchAll(/HikariDataSource - (.+?) - Shutdown initiated/g)].map(match=>match[1]);
  assert.ok(pools.length>0&&pools.every(pool=>log.includes('HikariDataSource - '+pool+' - Shutdown completed.')),'hikari_shutdown_incomplete');
  return {springComplete:true,entityManagerClosed:true,poolsClosed:pools.length};
}

/** Private local data only. No source connection is opened during this phase. */
export async function rehearse(output,{appEnvFile,appImage='otziv-app:finalization-shutdown-proof',resumePreparation=false,resumeViewDefiner=false,
  prepareViewDefiner=false,hardenedImage,retainAfterPass=false,publishedRollback=false}={}) {
  requireModeOptions({publishedRollback,resumePreparation,resumeViewDefiner,prepareViewDefiner,hardenedImage,retainAfterPass});
  assert.ok(appEnvFile,'app_env_file_required');
  resumePreparation=resumePreparation||resumeViewDefiner;
  const out=await realpath(resolve(output)),captureInfo=JSON.parse(await readFile(join(out,'capture.json'),'utf8'));
  const claim=JSON.parse(await readFile(join(out,'owner.json'),'utf8')),owner=claim.owner;
  assert.equal(await realpath(claim.output),out,'capture_owner_path_mismatch');
  assert.equal(owner,captureInfo.owner,'capture_owner_mismatch');
  requireCaptureMode(captureInfo,claim,{publishedRollback,resumePreparation,resumeViewDefiner,prepareViewDefiner,hardenedImage,retainAfterPass});
  assert.equal(captureInfo.sourceVersion,'9.0.0','source_version_mismatch');
  assert.match(captureInfo.database,/^[a-zA-Z0-9_]+$/,'schema_name_invalid');
  assert.equal(captureInfo.dump.file,'source.sql.gz','capture_dump_path_invalid');
  assert.equal(await fileDigest(join(out,captureInfo.dump.file)),captureInfo.dump.sha256,'captured_dump_checksum_mismatch');
  let sourceAccount;
  if(publishedRollback) {
    sourceAccount=JSON.parse(await readFile(join(out,'source-account-grants.json'),'utf8'));
    assert.deepEqual(requireAccountPolicy(sourceAccount,captureInfo.database),captureInfo.accountPolicy,'captured_account_policy_mismatch');
    assert.equal(digest(JSON.stringify(sourceAccount)),captureInfo.accountPolicySha256,'captured_account_policy_changed');
  }
  let previous,previousBytes,resumeNumber=0;
  if(resumePreparation) {
    previousBytes=await readFile(join(out,'rehearsal.json'),'utf8');previous=JSON.parse(previousBytes);
    const missingDefinerError=resumeViewDefiner&&previous.failurePhase==='mandatory_upgrade_checker';
    assert.ok(previous.result==='FAIL'&&(previous.failurePhase==='app_migration_9_0'||missingDefinerError)&&!previous.upgradeChecker&&!previous.runtimeVersion,'only_reviewed_preparation_can_resume');
    if(missingDefinerError) {
      const raw=await readFile(join(out,'upgrade-checker.json'),'utf8'),report=JSON.parse(raw);
      const errors=report.checksPerformed.flatMap(check=>(check.detectedProblems||[]).filter(problem=>problem.level==='Error').map(problem=>({check:check.id,...problem})));
      assert.equal(report.errorCount,2,'unexpected_checker_errors');
      assert.deepEqual(errors.map(error=>[error.check,error.dbObject,error.description]).sort(),[
        ['checkTableCommand','otziv.analytics_payment_source','Corrupt'],['checkTableCommand','otziv.analytics_salary_source','Corrupt']],'unexpected_checker_errors');
      assert.ok(report.checksPerformed.every(check=>check.status==='OK'),'incomplete_checker_cannot_resume');
      assert.ok(!previous.retainedResources.containers.some(name=>name.endsWith('-mysql973')),'target_already_started');
      await writeFile(join(out,'upgrade-checker-before-definer-fix.json'),raw,{flag:'wx',mode:0o600});
    }
    assert.equal(previous.owner,owner,'resume_owner_mismatch');
    resumeNumber=(previous.preparationFailurePreserved?.resumeNumber||Number(previous.preparationFailurePreserved?.file?.match(/-v(\d+)\.json$/)?.[1])||0)+1;
    await writeFile(join(out,'preparation-failed-v'+resumeNumber+'.json'),previousBytes,{flag:'wx',mode:0o600});
  }
  await writeFile(join(out,resumePreparation?'resume-preparation-v'+resumeNumber+'.claim':'rehearsal.claim'),new Date().toISOString(),{flag:'wx',mode:0o600});
  const network=owner+'-net',volume=owner+'-data',rollbackVolume=owner+'-rollback-data',database=captureInfo.database;
  const allocated=new Set(),password=resumePreparation?JSON.parse(await readFile(join(out,'clone-secret.json'),'utf8')).password:randomBytes(32).toString('hex');
  let networkCreated=false,volumeCreated=false,rollbackVolumeCreated=false,phase='preflight';
  let oldMysql,newMysql,currentMysql,activeApp;
  const evidence={schema:'otziv-mysql-upgrade-rehearsal-v1',...(publishedRollback?{mode:publishedMode,captureSchema:publishedCaptureSchema}:{}),owner,startedAt:new Date().toISOString(),production:false,
    sourceContainer:captureInfo.sourceContainer,sourceWrites:false,sourceCaptureSha256:captureInfo.dump.sha256,
    sourceSchemaAtCapture:captureInfo.sourceSchemaVersion||'1.10.300',database,images:{source:captureInfo.sourceImage,targetReference:publishedRollback?publishedImage:targetImage,targetConfigurationDigest:publishedRollback?publishedConfig:targetConfig,...(publishedRollback?{upgradeCheckerReference:targetImage}:{})},checks:previous?.checks||[],
    ...(previous?{preparationFailurePreserved:{file:'preparation-failed-v'+resumeNumber+'.json',sha256:digest(previousBytes),resumeNumber,resume:'SAME_9_0_VOLUME_ONLY_BEFORE_CHECKER'}}:{}),
    resourceLimits:{mysqlMemoryBytes:1610612736,appMemoryBytes:2147483648,mysqlCpus:2,appCpus:2},
    rollback:'NO_BINARY_DOWNGRADE: restore the pre-upgrade database with its paired application/configuration release',
    limitations:['local sanitized clone; not a production rollout','no performance SLO or fleet compatibility implied',
      'application health/Flyway/Hibernate only; Keycloak login and external delivery are not tested',publishedRollback?'the configured source account identity and grants are reproduced with a newly generated clone-only password; other source users are not copied':'source users/grants are not exported; isolated fixture accounts are used']};
  const save=()=>writeFile(join(out,'rehearsal.json'),JSON.stringify(evidence,null,2)+'\n',{mode:0o600});
  const docker=(args,options={})=>{const prepared=dockerEnvironment(args,{...process.env,...options.env});return run('docker',prepared.args,{...options,env:prepared.env});};
  const check=(name,condition)=>{assert.ok(condition,name);evidence.checks.push({name,passed:true,at:new Date().toISOString()});console.log('PASS '+name);};
  const owned=async name=>assert.equal((await docker(['inspect','--format',`{{index .Config.Labels "${label}"}}`,name])).trim(),owner,'container_owner_mismatch');
  async function create(role,image,args,command=[]) {
    const name=owner+'-'+role;
    await docker(['create','--pull=never','--name',name,'--label',label+'='+owner,'--network',network,
      '--security-opt','no-new-privileges:true','--pids-limit','384',...args,image,...command]);
    allocated.add(name);await docker(['start',name]);return name;
  }
  async function logs(name,filename) {
    // Logs stay under the same private ACL as the dump; they are never printed.
    try {
      await owned(name);const process=startProcess('docker',['logs',name],{maxOutput:16*1024*1024});process.collect();
      let stderr='';process.child.stderr.on('data',part=>{if(stderr.length<16*1024*1024)stderr+=part;});process.child.stdin.end();
      const stdout=await process.completed;await writeFile(join(out,filename),stdout+stderr,{mode:0o600});
    }catch {}
  }
  async function stop(name,seconds=120) {
    if(!name)return;await owned(name);const started=Date.now();
    const app=/-app(?:90|973)(?:-|$)/.test(name);
    const pid1=app?(await docker(['exec',name,'cat','/proc/1/comm'])).trim():undefined;
    await docker(['stop','--time',String(seconds),name],{timeoutMs:(seconds+20)*1000});
    const state=JSON.parse(await docker(['inspect','--format','{{json .State}}',name]));
    const database=/-mysql(?:90|973)(?:-|$)/.test(name);
    const stopped={container:name,elapsedMs:Date.now()-started,exitCode:state.ExitCode,oomKilled:state.OOMKilled,pid1};
    (evidence.stops||=[]).push(stopped);
    check('stopped_without_oom_or_forced_kill_'+name.split('-').at(-1),!state.Running&&!state.OOMKilled&&(database?state.ExitCode===0:app?pid1==='java'&&[0,143].includes(state.ExitCode):state.ExitCode===0));
    if(app) {
      const filename=name+'-shutdown.log';await logs(name,filename);
      stopped.graceful=requireGracefulAppStop(stopped,await readFile(join(out,filename),'utf8'),seconds*1000);
      check('completed_spring_jpa_hikari_shutdown_'+name.split('-').at(-1),true);
    }
  }
  const sql=(text,name=currentMysql)=>docker(['exec','-i','-e','MYSQL_PWD='+password,name,'mysql','--protocol=TCP','--host=127.0.0.1','--user=root','--database='+database,'--batch','--skip-column-names'],{input:input(text),maxOutput:16*1024*1024,timeoutMs:300000});
  async function waitDb(name,version) {
    const deadline=Date.now()+240000;
    while(Date.now()<deadline) {
      if((await docker(['inspect','--format','{{.State.Running}}',name])).trim()!=='true')throw Error('mysql_container_exited');
      try {const actual=(await sql('SELECT VERSION();',name)).trim();assert.equal(actual,version);return actual;}catch {}
      await pause(1000);
    }
    throw Error('mysql_readiness_timeout');
  }
  async function startApp(role) {
    // Explicit external-effect flags plus an internal-only network. Imported local
    // encryption keys stay in Docker's env-file transport; never print the env.
    const env={SPRING_PROFILES_ACTIVE:'prod',JAVA_OPTS:'-Xms128m -Xmx1000m -XX:MaxMetaspaceSize=384m -Djava.awt.headless=true',
      DATABASE_URL:'jdbc:mysql://mysql:3306/'+database+'?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
      MYSQL_DATABASE:database,MYSQL_USER:publishedRollback?sourceAccount.user:'fixture',MYSQL_PASSWORD:password,
      KEYCLOAK_ISSUER_URI:'http://unavailable.invalid/realms/fixture',KEYCLOAK_JWK_SET_URI:'http://unavailable.invalid/realms/fixture/protocol/openid-connect/certs',
      KEYCLOAK_ADMIN_SERVER_URL:'http://unavailable.invalid',OTZIV_SECURITY_SESSION_REVOCATION_MODE:'off',
      KEYCLOAK_ADMIN_CLIENT_SECRET:randomBytes(24).toString('hex'),
      OTZIV_SECURITY_SESSION_REVOCATION_DISPATCH_ENABLED:'false',OTZIV_SECURITY_ISSUER_GENERATION_REQUIRED:'false',
      TELEGRAM_BOT_TOKEN:'fixture-disabled',TELEGRAM_BOT_USERNAME:'fixture-disabled',TELEGRAM_BOT_REGISTRATION_ENABLED:'false',TELEGRAM_BOT_SENDING_ENABLED:'false',TELEGRAM_ADMIN_CHAT_IDS:'',
      MAIL_HOST:'unavailable.invalid',MAIL_USERNAME:'fixture@example.invalid',MAIL_PASSWORD:randomBytes(24).toString('hex'),
      OPENAI_API_KEY:randomBytes(24).toString('hex'),JWT_SECRET:randomBytes(48).toString('base64'),
      S3_ENDPOINT:'http://unavailable.invalid',S3_ACCESS_KEY:'fixture',S3_SECRET_KEY:randomBytes(24).toString('hex'),S3_BUCKET:'fixture',S3_PROJECT:'fixture',S3_REGION:'us-east-1',
      LEAD_TRANSFER_URL:'http://unavailable.invalid',LEAD_SYNCHRONY_URL:'http://unavailable.invalid',LEAD_UPDATE_URL:'http://unavailable.invalid',LEAD_VPS_URL:'http://unavailable.invalid',
      OTZIV_LEGACY_ENABLED:'false',BACKUP_SCHEDULE_ENABLED:'false',OTZIV_PAYMENTS_TBANK_ENABLED:'false',
      OTZIV_INTEGRATION_OUTBOX_RELAY_ENABLED:'false',PERFORMERS_NOTIFICATIONS_DISPATCH_ENABLED:'false',
      LEAD_COMMANDS_DISPATCH_ENABLED:'false',LEAD_COMMANDS_RECEIVER_ENABLED:'false',
      MAX_BOT_WEBHOOK_AUTO_REGISTER_ENABLED:'false',MAX_BOT_LONG_POLLING_ENABLED:'false',WHATSAPP_HEALTH_MONITOR_ENABLED:'false',
      OTZIV_ARCHIVE_ORDERS_APPLY_ENABLED:'false',OTZIV_ARCHIVE_ORDERS_SCHEDULE_ENABLED:'false',OTZIV_ANALYTICS_REBUILD_API_ENABLED:'false',
      OTZIV_ANALYTICS_REBUILD_STARTUP_ENABLED:'false',OTZIV_ANALYTICS_REBUILD_SCHEDULE_ENABLED:'false',OTZIV_REMINDER_NOTIFICATIONS_ENABLED:'false',
      OTZIV_CREDENTIAL_ENCRYPTION_BACKFILL_ENABLED:'false',SPRING_MAIL_TEST_CONNECTION:'false',
      MANAGEMENT_HEALTH_MAIL_ENABLED:'false',LOGGING_LEVEL_ORG_FLYWAYDB:'INFO',LOGGING_LEVEL_ORG_HIBERNATE_TOOL_SCHEMA:'INFO',
      LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT:'INFO',
      LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_ORM_JPA:'INFO',LOGGING_LEVEL_COM_ZAXXER_HIKARI:'INFO',
      OTZIV_LOG_PATH:'/tmp/logs',OTZIV_ROOT_LOG_LEVEL:'WARN',OTZIV_APP_LOG_LEVEL:'WARN'};
    activeApp=await create(role,evidence.images.app,['--env-file',resolve(appEnvFile),'--read-only','--cap-drop','ALL',
      '--memory','2g','--cpus','2','--tmpfs','/tmp:rw,noexec,nosuid,size=256m,mode=1777',
      ...Object.entries(env).flatMap(([key,value])=>['-e',key+'='+value])]);
    const deadline=Date.now()+360000;
    while(Date.now()<deadline) {
      if((await docker(['inspect','--format','{{.State.Running}}',activeApp])).trim()!=='true')throw Error('app_container_exited');
      try {
        const health=JSON.parse(await docker(['exec',activeApp,'curl','--fail','--silent','--max-time','5','http://127.0.0.1:8080/actuator/health']));
        if(health.status==='UP'){await writeFile(join(out,role+'-health.json'),JSON.stringify(health,null,2),{mode:0o600});check(role+'_health_up',true);return;}
      }catch {}
      await pause(1500);
    }
    throw Error('app_health_timeout');
  }
  async function snapshot(prefix) {
    const tables=(await sql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;")).trim().split('\n');
    assert.ok(tables.every(table=>/^[a-zA-Z0-9_]+$/.test(table)),'table_name_invalid');
    const counts=await sql(tables.map(table=>"SELECT '"+table+"',COUNT(*) FROM `"+table+'`;').join('\n'));
    const history=await sql('SELECT installed_rank,version,description,type,script,COALESCE(checksum,0),success FROM flyway_schema_history ORDER BY installed_rank;');
    const checksumTables=['orders','reviews','lead','leads','common_invoices','payment_links','lead_command_queue','performer_assignments','performer_notification_intents'].filter(table=>tables.includes(table));
    assert.ok(checksumTables.includes('orders')&&checksumTables.includes('lead_command_queue')&&checksumTables.includes('performer_notification_intents'),'representative_tables_missing');
    const checksums=await sql('CHECKSUM TABLE '+checksumTables.map(table=>'`'+table+'`').join(',')+' EXTENDED;');
    assert.ok(!checksums.includes('\tNULL'),'table_checksum_unavailable');
    const jsonSql="SELECT JSON_UNQUOTE(JSON_EXTRACT(JSON_OBJECT('array',JSON_ARRAY(1,true,NULL,'Юникод'),'nested',JSON_OBJECT('amount',12.34)),'$.array[3]')),JSON_CONTAINS(JSON_ARRAY(1,2,3),'2'),JSON_UNQUOTE(JSON_EXTRACT(JSON_OBJECT('status','UNKNOWN'),'$.status'));";
    const json=await sql(jsonSql);
    const queueSql="START TRANSACTION; SELECT q.id FROM lead_command_queue q WHERE q.delivery_state='READY' AND q.retry_count<8 AND q.next_attempt_at<=UTC_TIMESTAMP(6) AND NOT EXISTS (SELECT 1 FROM lead_command_queue earlier WHERE earlier.blocking_lead_id=q.lead_id AND earlier.id<q.id) ORDER BY q.next_attempt_at,q.id LIMIT 1 FOR UPDATE SKIP LOCKED; SELECT notification_id FROM performer_notification_intents WHERE status='PENDING' AND due_at<=CURRENT_TIMESTAMP(6) ORDER BY due_at,notification_id LIMIT 1 FOR UPDATE SKIP LOCKED; ROLLBACK;";
    await sql(queueSql); // Only row locks, no claim mutation or provider dispatch.
    const jsonColumns=await sql("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND DATA_TYPE='json' ORDER BY TABLE_NAME,ORDINAL_POSITION;");
    const jsonChecks=[];
    for(const row of jsonColumns.trim().split('\n').filter(Boolean)) {
      const [table,column]=row.split('\t');assert.match(table,/^[a-zA-Z0-9_]+$/);assert.match(column,/^[a-zA-Z0-9_]+$/);
      jsonChecks.push("SELECT '"+table+'.'+column+"',COUNT(`"+column+'`),COALESCE(SUM(JSON_VALID(`'+column+'`)),0),BIT_XOR(CRC32(CAST(`'+column+'` AS CHAR))) FROM `'+table+'`;');
    }
    const jsonData=jsonChecks.length?await sql(jsonChecks.join('\n')):'';
    for(const [name,value] of Object.entries({counts,history,checksums,json,jsonData}))await writeFile(join(out,prefix+'-'+name+'.tsv'),value,{mode:0o600});
    return {tableCount:tables.length,rows:counts.trim().split('\n').reduce((sum,row)=>sum+Number(row.split('\t')[1]),0),countsSha256:digest(counts),historySha256:digest(history),
      checksumsSha256:digest(checksums),jsonSha256:digest(json),jsonDataSha256:digest(jsonData),jsonColumns:jsonChecks.length,
      checksumTables,queries:{json:jsonSql,jsonData:jsonChecks,queueSelection:queueSql},queryHashes:{json:digest(jsonSql),queueSelection:digest(queueSql)},
      queueProof:'native SELECT FOR UPDATE SKIP LOCKED with ROLLBACK; no claims, sends or saturation assertion'};
  }
  async function prepareConfiguredAccount() {
    await sql("CREATE USER '"+sourceAccount.user+"'@'%' IDENTIFIED BY '"+password+"';");
    await sql(sourceAccount.grants.join(';\n')+';');
    const actual=(await sql("SHOW GRANTS FOR '"+sourceAccount.user+"'@'%';")).trim().split('\n');
    assert.deepEqual(actual.sort(),[...sourceAccount.grants].sort(),'cloned_application_grants_changed');
    const accountProof=await docker(['exec','-i','-e','MYSQL_PWD='+password,currentMysql,'mysql','--protocol=TCP','--host=127.0.0.1','--user='+sourceAccount.user,'--database='+database,'--batch','--skip-column-names'],
      {input:input('SELECT CURRENT_USER(),DATABASE(); SELECT COUNT(*) FROM flyway_schema_history;')});
    assert.ok(accountProof.startsWith(sourceAccount.user+'@%\t'+database+'\n'),'configured_application_account_login_failed');
    evidence.configuredAccount={policy:captureInfo.accountPolicy,sourcePolicySha256:captureInfo.accountPolicySha256,clonedGrantsEqual:true,originalPasswordCopied:false};
  }
  async function dumpOwnedBackup(filename) {
    await owned(currentMysql);
    const args=dockerEnvironment(['exec','-e','MYSQL_PWD='+password,currentMysql,'mysqldump','--protocol=TCP','--host=127.0.0.1','--user=root','--single-transaction','--quick','--routines','--triggers','--events','--no-tablespaces','--hex-blob','--set-gtid-purged=OFF',database]);
    const child=startProcess('docker',args.args,{env:args.env,timeoutMs:600000});child.child.stdin.end();
    const results=await Promise.allSettled([pipeline(child.child.stdout,createGzip({level:1}),createWriteStream(join(out,filename),{flags:'wx',mode:0o600})),child.completed]);
    assert.ok(results.every(result=>result.status==='fulfilled'),'preupgrade_backup_failed');
    return {file:filename,sha256:await fileDigest(join(out,filename)),bytes:(await stat(join(out,filename))).size};
  }
  try {
    assert.match((await docker(['context','inspect','--format','{{.Endpoints.docker.Host}}'])).trim(),/^(npipe|unix):\/\//);
    evidence.images.app=(await docker(['image','inspect','--format','{{.Id}}',appImage])).trim();
    if(publishedRollback) {
      const publishedMetadata=JSON.parse(await docker(['image','inspect','--format','{{json .}}',publishedImage]));
      evidence.images.published={reference:publishedImage,configurationDigest:publishedConfig,localId:requirePublishedIdentity(publishedMetadata)};
    }
    if(hardenedImage){evidence.images.hardenedReference=hardenedImage;evidence.images.hardened=(await docker(['image','inspect','--format','{{.Id}}',hardenedImage])).trim();}
    const imageMetadata=JSON.parse(await docker(['image','inspect','--format','{{json .}}',targetImage]));
    assert.ok(imageMetadata.RepoDigests.includes(targetImage)&&imageMetadata.Architecture==='amd64'&&imageMetadata.Os==='linux','target_image_mismatch');
    // containerd image-store IDs identify the manifest; classic Docker IDs identify
    // its config blob. Always execute the verified repository manifest digest.
    assert.ok([targetConfig,targetImage.split('@')[1]].includes(imageMetadata.Id),'target_local_identity_mismatch');
    evidence.images.targetLocalId=publishedRollback?evidence.images.published.localId:imageMetadata.Id;
    if(publishedRollback)evidence.images.upgradeCheckerLocalId=imageMetadata.Id;
    await writeFile(join(out,'runtime-stats-before.txt'),await docker(['stats','--no-stream','--format','{{.Name}} {{.MemUsage}} {{.CPUPerc}}']),{mode:0o600});
    if(resumePreparation) {
      if(evidence.images.app!==previous.images.app) {
        const oldImage=JSON.parse(await docker(['image','inspect','--format','{{json .}}',previous.images.app]));
        const newImage=JSON.parse(await docker(['image','inspect','--format','{{json .}}',evidence.images.app]));
        assert.deepEqual(newImage.RootFS,oldImage.RootFS,'resume_application_files_changed');
        assert.deepEqual(newImage.Config.Entrypoint,['sh','-c','exec java ${JAVA_OPTS:-} -jar /app/app.jar'],'resume_unreviewed_entrypoint');
        const oldConfig={...oldImage.Config},newConfig={...newImage.Config};delete oldConfig.Entrypoint;delete newConfig.Entrypoint;
        assert.deepEqual(newConfig,oldConfig,'resume_unreviewed_image_configuration');
        evidence.correctedAppEntrypoint={previous:previous.images.app,current:evidence.images.app,rootFsIdentical:true,entrypoint:newImage.Config.Entrypoint};
      }
      assert.equal(previous.retainedResources.network,network,'resume_network_changed');assert.equal(previous.retainedResources.volume,volume,'resume_volume_changed');
      assert.equal((await docker(['network','inspect','--format',`{{index .Labels "${label}"}}`,network])).trim(),owner,'resume_network_owner_mismatch');
      assert.equal((await docker(['network','inspect','--format','{{.Internal}}',network])).trim(),'true','resume_network_external');
      assert.equal((await docker(['volume','inspect','--format',`{{index .Labels "${label}"}}`,volume])).trim(),owner,'resume_volume_owner_mismatch');
      for(const name of previous.retainedResources.containers){await owned(name);assert.equal((await docker(['inspect','--format','{{.State.Running}}',name])).trim(),'false','resume_container_still_running');allocated.add(name);}
      networkCreated=true;volumeCreated=true;
    } else {
      await writeFile(join(out,'clone-secret.json'),JSON.stringify({password}),{flag:'wx',mode:0o600});
      await docker(['network','create','--internal','--label',label+'='+owner,network]);networkCreated=true;
      await docker(['volume','create','--label',label+'='+owner,volume]);volumeCreated=true;
    }
    const config='[mysqld]\nhost-cache-size=0\nskip-name-resolve\ndatadir=/var/lib/mysql\nsocket=/var/lib/mysql/mysql.sock\nsecure-file-priv=/var/lib/mysql-files\nuser=mysql\npid-file=/var/lib/mysql/mysqld.pid\ninnodb_buffer_pool_size=256M\nmax_connections=70\nrestrict_fk_on_non_standard_key=OFF\nevent_scheduler=OFF\ndefault-time-zone=+00:00\n[client]\nsocket=/var/lib/mysql/mysql.sock\n';
    if(resumePreparation)assert.equal(digest(await readFile(join(out,'my.cnf'))),digest(config),'resume_config_changed');
    else await writeFile(join(out,'my.cnf'),config,{mode:0o600});evidence.configurationSha256=digest(config);
    const mysqlArgs=['--network-alias','mysql','--memory','1536m','--cpus','2','--mount','type=volume,source='+volume+',target=/var/lib/mysql',
      '--mount','type=bind,source='+join(out,'my.cnf')+',target=/fixture/my.cnf,readonly'];
    phase='clone_9_0';
    if(resumePreparation) {
      oldMysql=owner+'-mysql90';assert.ok(allocated.has(oldMysql),'resume_mysql_missing');
      assert.equal((await docker(['inspect','--format','{{.Image}}',oldMysql])).trim(),captureInfo.sourceImage,'resume_source_image_changed');
      await docker(['start',oldMysql]);
    } else oldMysql=await create('mysql90',captureInfo.sourceImage,[...mysqlArgs,
      ...Object.entries({MYSQL_ROOT_PASSWORD:password,MYSQL_ROOT_HOST:'%',MYSQL_DATABASE:database,MYSQL_USER:'fixture',MYSQL_PASSWORD:password})
        .flatMap(([key,value])=>['-e',key+'='+value])],['mysqld','--defaults-file=/fixture/my.cnf']);
    currentMysql=oldMysql;await waitDb(oldMysql,'9.0.0');check('clone_9_0_authenticated',true);
    if(!resumePreparation) {
    phase='import_capture';
    await sql('SET GLOBAL log_bin_trust_function_creators=ON;');
    const importArgs=dockerEnvironment(['exec','-i','-e','MYSQL_PWD='+password,oldMysql,'mysql','--protocol=TCP','--host=127.0.0.1','--user=root','--database='+database]);
    const importer=startProcess('docker',importArgs.args,{env:importArgs.env,timeoutMs:600000});importer.collect();
    const imported=await Promise.allSettled([pipeline(createReadStream(join(out,captureInfo.dump.file)),createGunzip(),importer.child.stdin),importer.completed]);
    assert.ok(imported.every(result=>result.status==='fulfilled'),'clone_import_failed');
    const originalHistory=await sql('SELECT installed_rank,version,description,type,script,COALESCE(checksum,0),success FROM flyway_schema_history ORDER BY installed_rank;');
    check('captured_flyway_history_restored',digest(originalHistory)===captureInfo.flywayHistorySha256);
    }
    if(publishedRollback) {await prepareConfiguredAccount();check('configured_local_account_grants_and_login_preserved',true);}
    if(resumeViewDefiner||prepareViewDefiner) {
      phase='reviewed_clone_definer_preparation';
      const viewSql="SELECT TABLE_NAME,DEFINER,SECURITY_TYPE,SHA2(VIEW_DEFINITION,256) FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('analytics_payment_source','analytics_salary_source') ORDER BY TABLE_NAME;";
      const viewsBefore=await sql(viewSql),viewRows=viewsBefore.trim().split('\n').map(row=>row.split('\t'));
      assert.deepEqual(viewRows.map(row=>row.slice(0,3)),[['analytics_payment_source','hunt@%','DEFINER'],['analytics_salary_source','hunt@%','DEFINER']],'unexpected_view_definer');
      assert.equal((await sql("SELECT COUNT(*) FROM mysql.user WHERE User='hunt' AND Host='%';")).trim(),'0','definer_account_already_exists');
      await sql("CREATE USER 'hunt'@'%' IDENTIFIED BY '"+randomBytes(32).toString('hex')+"' ACCOUNT LOCK; GRANT SELECT ON `"+database+"`.* TO 'hunt'@'%';");
      assert.equal((await sql("SELECT account_locked FROM mysql.user WHERE User='hunt' AND Host='%';")).trim(),'Y','definer_account_not_locked');
      const privileges=(await sql("SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=CONCAT(CHAR(39),'hunt',CHAR(39),'@',CHAR(39),'%',CHAR(39)) AND TABLE_SCHEMA=DATABASE() ORDER BY PRIVILEGE_TYPE;")).trim();
      assert.equal(privileges,'SELECT','unexpected_definer_privilege');
      assert.equal(await sql(viewSql),viewsBefore,'view_definition_changed');
      const checked=await sql('CHECK TABLE analytics_salary_source,analytics_payment_source FOR UPGRADE;');
      assert.ok(!/\t(?:Error|error)\t/.test(checked)&&checked.trim().split('\n').every(row=>row.endsWith('\tstatus\tOK')),'definer_preparation_did_not_fix_views');
      evidence.syntheticDefinerPreparation={account:'hunt@%',locked:true,privileges:['SELECT ON '+database+'.*'],productionPasswordUsed:false,
        viewDefinitionsUnchanged:true,viewMetadataSha256:digest(viewsBefore),originalFailure:resumeViewDefiner?'upgrade-checker-before-definer-fix.json':'separately reviewed missing-definer diagnosis in original rehearsal',sourceWrites:false};
      await writeFile(join(out,'view-check-after-definer-fix.tsv'),checked,{mode:0o600});check('reviewed_locked_read_only_definer_restored',true);
    }
    phase='app_migration_9_0';await startApp(resumePreparation?'app90-retry'+resumeNumber:'app90');await stop(activeApp);await logs(activeApp,'app90.log');activeApp=undefined;
    const latest=(await sql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;')).trim();
    check('actual_app_migrated_to_1_10_306',latest==='1.10.306');
    if(publishedRollback) {
      await sql('CREATE TABLE otziv_c7_rollback_probe (id INT PRIMARY KEY, note VARCHAR(64) NOT NULL); INSERT INTO otziv_c7_rollback_probe VALUES (1,\'before-upgrade\');');
    }
    phase='snapshot_9_0';evidence.before=await snapshot('before');await save();
    if(publishedRollback) {
      phase='preupgrade_backup';evidence.preupgradeBackup=await dumpOwnedBackup('preupgrade.sql.gz');
      check('preupgrade_backup_created_with_application_stopped',!activeApp&&evidence.preupgradeBackup.bytes>100);
    }
    phase='mandatory_upgrade_checker';
    const checkerScript="util.checkForServerUpgrade({user:'root',host:'mysql',port:3306,password:os.getenv('MYSQL_PWD')},{targetVersion:'9.7.3',outputFormat:'JSON',configPath:'/fixture/my.cnf'});\n";
    await writeFile(join(out,'checker.js'),checkerScript,{mode:0o600});
    const checker=await create(resumeViewDefiner?'checker-definer-fixed':'checker',targetImage,['--memory','768m','--cpus','2','--entrypoint','mysqlsh','-e','MYSQL_PWD='+password,
      '--mount','type=bind,source='+join(out,'my.cnf')+',target=/fixture/my.cnf,readonly',
      '--mount','type=bind,source='+join(out,'checker.js')+',target=/fixture/checker.js,readonly'],['--js','--quiet-start=2','--log-level=1','--log-file=/tmp/mysqlsh.log','--file=/fixture/checker.js']);
    const checkerExit=Number((await docker(['wait',checker],{timeoutMs:300000})).trim());
    const checkerOutput=await docker(['logs',checker],{maxOutput:8*1024*1024});
    await writeFile(join(out,'upgrade-checker.json'),checkerOutput,{mode:0o600});
    assert.equal(checkerExit,0,'upgrade_checker_process_failed');
    const checkerReport=JSON.parse(checkerOutput);
    evidence.upgradeCheckerObserved={errors:checkerReport.errorCount,warnings:checkerReport.warningCount,notices:checkerReport.noticeCount,
      completedChecks:checkerReport.checksPerformed?.length,manualChecks:checkerReport.manualChecks||[]};
    evidence.upgradeChecker=requireUpgradeCheck(checkerReport);check('mandatory_upgrade_checker_passed',true);await save();
    phase='consistent_shutdown';await sql('SET GLOBAL innodb_fast_shutdown=0;');await stop(oldMysql,180);await logs(oldMysql,'mysql90.log');
    const oldMounts=JSON.parse(await docker(['inspect','--format','{{json .Mounts}}',oldMysql]));
    assert.equal(oldMounts.find(mount=>mount.Destination==='/var/lib/mysql')?.Name,volume,'old_volume_binding_mismatch');
    phase='in_place_9_7_3';
    // Preserve UID999 ownership from the source image. Oracle's entrypoint explicitly
    // supports a numeric non-root caller; writable runtime files have a dedicated tmpfs.
    newMysql=await create('mysql973',publishedRollback?publishedImage:targetImage,[...mysqlArgs,'--user','999:999','--tmpfs','/var/lib/mysql-files:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0700'],['mysqld','--defaults-file=/fixture/my.cnf']);
    currentMysql=newMysql;await waitDb(newMysql,'9.7.3');
    const newMounts=JSON.parse(await docker(['inspect','--format','{{json .Mounts}}',newMysql]));
    check('same_named_data_volume_upgraded',newMounts.find(mount=>mount.Destination==='/var/lib/mysql')?.Name===volume);
    evidence.runtimeVersion=(await sql('SELECT VERSION();')).trim();check('target_9_7_3_authenticated',evidence.runtimeVersion==='9.7.3');
    phase='snapshot_9_7_3';evidence.after=await snapshot('after');
    for(const key of ['tableCount','rows','countsSha256','historySha256','checksumsSha256','jsonSha256','jsonDataSha256'])check('unchanged_'+key,evidence.before[key]===evidence.after[key]);
    phase='app_9_7_3';await startApp('app973');await stop(activeApp);await logs(activeApp,'app973.log');activeApp=undefined;
    check('flyway_still_1_10_306',(await sql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;')).trim()==='1.10.306');
    await logs(newMysql,'mysql973.log');
    if(hardenedImage) {
      // Freeze upstream evidence before starting the additional hardened-server phase.
      await writeFile(join(out,'upstream-rehearsal.json'),JSON.stringify({...evidence,result:'PASS',completedAt:new Date().toISOString(),cleanup:'DEFERRED_FOR_HARDENED_SAME_VOLUME_PROOF'},null,2)+'\n',{flag:'wx',mode:0o600});
      phase='hardened_preparation';evidence.hardenedBefore=await snapshot('hardened-before');
      await sql('SET GLOBAL innodb_fast_shutdown=0;');await stop(newMysql,180);
      phase='hardened_9_7_3';
      newMysql=await create('mysql973-hardened',evidence.images.hardened,[...mysqlArgs,'--user','999:999','--tmpfs','/var/lib/mysql-files:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0700'],['mysqld','--defaults-file=/fixture/my.cnf']);
      currentMysql=newMysql;await waitDb(newMysql,'9.7.3');
      const hardMounts=JSON.parse(await docker(['inspect','--format','{{json .Mounts}}',newMysql]));
      check('hardened_same_named_data_volume',hardMounts.find(mount=>mount.Destination==='/var/lib/mysql')?.Name===volume);
      evidence.hardenedAfter=await snapshot('hardened-after');
      for(const key of ['tableCount','rows','countsSha256','historySha256','checksumsSha256','jsonSha256','jsonDataSha256'])check('hardened_unchanged_'+key,evidence.hardenedBefore[key]===evidence.hardenedAfter[key]);
      phase='app_hardened_9_7_3';await startApp('app973-hardened');await stop(activeApp);await logs(activeApp,'app973-hardened.log');activeApp=undefined;
      const restartBefore=await snapshot('restart-before');await sql('SET GLOBAL innodb_fast_shutdown=0;');await stop(newMysql,180);
      phase='hardened_restart';await docker(['start',newMysql]);await waitDb(newMysql,'9.7.3');
      const restartAfter=await snapshot('restart-after');
      for(const key of ['tableCount','rows','countsSha256','historySha256','checksumsSha256','jsonSha256','jsonDataSha256'])check('hardened_restart_unchanged_'+key,restartBefore[key]===restartAfter[key]);
      evidence.hardenedRestart={before:restartBefore,after:restartAfter};
      await logs(newMysql,'mysql973-hardened.log');
      evidence.hardenedRuntimeVersion=(await sql('SELECT VERSION();')).trim();
    }
    if(publishedRollback) {
      phase='published_candidate_account_and_mutation';
      const upgradedGrants=(await sql("SHOW GRANTS FOR '"+sourceAccount.user+"'@'%';")).trim().split('\n');
      assert.deepEqual(upgradedGrants.sort(),[...sourceAccount.grants].sort(),'upgraded_application_grants_changed');
      await sql("INSERT INTO otziv_c7_rollback_probe VALUES (2,'after-upgrade');");
      check('published_candidate_accepts_new_canary_write',(await sql('SELECT COUNT(*) FROM otziv_c7_rollback_probe;')).trim()==='2');
      const restartBefore=await snapshot('published-restart-before');
      await sql('SET GLOBAL innodb_fast_shutdown=0;');await stop(newMysql,180);
      phase='published_candidate_restart';await docker(['start',newMysql]);await waitDb(newMysql,'9.7.3');
      const restartAfter=await snapshot('published-restart-after');
      for(const key of ['tableCount','rows','countsSha256','historySha256','checksumsSha256','jsonSha256','jsonDataSha256'])check('published_restart_unchanged_'+key,restartBefore[key]===restartAfter[key]);
      check('published_restart_keeps_postupgrade_canary',(await sql('SELECT COUNT(*) FROM otziv_c7_rollback_probe;')).trim()==='2');
      await sql('SET GLOBAL innodb_fast_shutdown=0;');await stop(newMysql,180);
      phase='rollback_fresh_volume';
      assert.equal(await fileDigest(join(out,evidence.preupgradeBackup.file)),evidence.preupgradeBackup.sha256,'rollback_backup_changed');
      await docker(['volume','create','--label',label+'='+owner,rollbackVolume]);rollbackVolumeCreated=true;
      assert.equal((await docker(['volume','inspect','--format',`{{index .Labels "${label}"}}`,rollbackVolume])).trim(),owner,'rollback_volume_owner_mismatch');
      const rollbackArgs=[...mysqlArgs];
      const oldMount='type=volume,source='+volume+',target=/var/lib/mysql';
      assert.equal(rollbackArgs.filter(arg=>arg===oldMount).length,1,'rollback_source_mount_ambiguous');
      rollbackArgs[rollbackArgs.indexOf(oldMount)]='type=volume,source='+rollbackVolume+',target=/var/lib/mysql';
      const restoredMysql=await create('mysql90-rollback',captureInfo.sourceImage,[...rollbackArgs,
        ...Object.entries({MYSQL_ROOT_PASSWORD:password,MYSQL_ROOT_HOST:'%',MYSQL_DATABASE:database,MYSQL_USER:'fixture',MYSQL_PASSWORD:password}).flatMap(([key,value])=>['-e',key+'='+value])],['mysqld','--defaults-file=/fixture/my.cnf']);
      currentMysql=restoredMysql;await waitDb(restoredMysql,'9.0.0');
      const restoredMounts=JSON.parse(await docker(['inspect','--format','{{json .Mounts}}',restoredMysql]));
      check('rollback_uses_separate_fresh_old_version_volume',rollbackVolume!==volume&&restoredMounts.find(m=>m.Destination==='/var/lib/mysql')?.Name===rollbackVolume);
      check('rollback_database_was_initially_empty',(await sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();")).trim()==='0');
      await sql('SET GLOBAL log_bin_trust_function_creators=ON;');
      const restoreArgs=dockerEnvironment(['exec','-i','-e','MYSQL_PWD='+password,restoredMysql,'mysql','--protocol=TCP','--host=127.0.0.1','--user=root','--database='+database]);
      const restore=startProcess('docker',restoreArgs.args,{env:restoreArgs.env,timeoutMs:600000});restore.collect();
      const restored=await Promise.allSettled([pipeline(createReadStream(join(out,evidence.preupgradeBackup.file)),createGunzip(),restore.child.stdin),restore.completed]);
      assert.ok(restored.every(result=>result.status==='fulfilled'),'rollback_import_failed');
      await prepareConfiguredAccount();
      const rollbackSnapshot=await snapshot('rollback');
      for(const key of ['tableCount','rows','countsSha256','historySha256','checksumsSha256','jsonSha256','jsonDataSha256'])check('rollback_restores_preupgrade_'+key,evidence.before[key]===rollbackSnapshot[key]);
      check('postupgrade_canary_absent_after_backup_restore',(await sql("SELECT GROUP_CONCAT(CONCAT(id,':',note) ORDER BY id) FROM otziv_c7_rollback_probe;")).trim()==='1:before-upgrade');
      phase='rollback_application';await startApp('app90-rollback');await stop(activeApp);await logs(activeApp,'app90-rollback.log');activeApp=undefined;
      check('rollback_application_keeps_flyway_history',(await sql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;')).trim()==='1.10.306');
      await sql('SET GLOBAL innodb_fast_shutdown=0;');await stop(restoredMysql,180);
      evidence.rollbackProof={result:'PASS',sourceImage:captureInfo.sourceImage,restoredVersion:'9.0.0',backup:evidence.preupgradeBackup,
        originalVolume:volume,restoredVolume:rollbackVolume,postUpgradeWriteAbsent:true,configuredAccountGrantsPreserved:true,
        applicationImage:evidence.images.app,snapshot:rollbackSnapshot,binaryDowngradeAttempted:false};
    }
    evidence.result='PASS';phase='cleanup';
  }catch(error) {
    evidence.result='FAIL';evidence.failurePhase=phase;evidence.failureCode=/^[a-z_]+$/.test(error.message||'')?error.message:'mysql_upgrade_rehearsal_failed';
    // No --upgrade=FORCE, grant bypass, fresh data directory or downgrade fallback.
    evidence.failedVolumeRetained=true;console.error(JSON.stringify({result:'FAIL',phase,code:evidence.failureCode}));
  }finally {
    for(const name of allocated) {
      await logs(name,name.split('-').at(-1)+'-final.log');
      try {if((await docker(['inspect','--format','{{.State.Running}}',name])).trim()==='true')await stop(name,120);}catch {evidence.cleanupFailure=true;}
    }
    if(evidence.cleanupFailure)evidence.result='FAIL';
    if(evidence.result==='PASS'&&!retainAfterPass) {
      for(const name of allocated){await owned(name);await docker(['rm','-v',name]);}
      if(volumeCreated){assert.equal((await docker(['volume','inspect','--format',`{{index .Labels "${label}"}}`,volume])).trim(),owner);await docker(['volume','rm',volume]);}
      if(rollbackVolumeCreated){assert.equal((await docker(['volume','inspect','--format',`{{index .Labels "${label}"}}`,rollbackVolume])).trim(),owner);await docker(['volume','rm',rollbackVolume]);}
      if(networkCreated){assert.equal((await docker(['network','inspect','--format',`{{index .Labels "${label}"}}`,network])).trim(),owner);await docker(['network','rm',network]);}
      // Delete only the exact mode-specific files inside this run's verified, non-symlink owner path.
      for(const file of [captureInfo.dump.file,'clone-secret.json',...(publishedRollback?['preupgrade.sql.gz']:[])]) {
        const path=await realpath(join(out,file));assert.ok(path.startsWith(out+'\\')||path.startsWith(out+'/'),'cleanup_path_outside_owned_output');
        assert.equal(path,join(out,file),'cleanup_symlink_refused');await unlink(path);
      }
      evidence.cleanup='OWNED_CONTAINERS_NETWORK_VOLUME_AND_PROTECTED_DUMP_REMOVED';
    } else {
      evidence.retainedResources={containers:[...allocated],network:networkCreated?network:null,volume:volumeCreated?volume:null,...(publishedRollback?{rollbackVolume:rollbackVolumeCreated?rollbackVolume:null}:{}),stopped:true};
      if(evidence.result==='PASS')evidence.cleanup='EXPLICIT_RETAIN_AFTER_PASS_STOPPED_OWNED_RESOURCES';
    }
    evidence.completedAt=new Date().toISOString();await save();
  }
  assert.equal(evidence.result,'PASS','mysql_upgrade_rehearsal_failed');
  console.log(JSON.stringify({result:'PASS',output:out,version:evidence.runtimeVersion,checks:evidence.checks.length}));return evidence;
}

export function parseRehearsalCli(args) {
  const [command,output,...flags]=args;assert.ok(output,'output_required');
  assert.ok(['capture','rehearse'].includes(command),'expected_capture_or_rehearse_command');
  const publishedRollback=flags.includes('--published-rollback');
  if(publishedRollback) {
    const booleanFlags=new Set(command==='capture'?['--published-rollback','--source-ready']:['--published-rollback']);
    const valueFlags=new Set(command==='rehearse'?['--app-env-file','--app-image']:[]),seen=new Set();
    for(let i=0;i<flags.length;i++) {
      const flag=flags[i];
      assert.ok(booleanFlags.has(flag)||valueFlags.has(flag),'published_rollback_unreviewed_flag');
      assert.ok(!seen.has(flag),'published_rollback_duplicate_flag');seen.add(flag);
      if(valueFlags.has(flag))assert.ok(flags[++i]&&!flags[i].startsWith('--'),'published_rollback_flag_value_required');
    }
  }
  if(command==='capture')return {command,output,options:{sourceReady:flags.includes('--source-ready'),publishedRollback}};
  return {command,output,options:{appEnvFile:flags.includes('--app-env-file')?flags[flags.indexOf('--app-env-file')+1]:undefined,
    appImage:flags.includes('--app-image')?flags[flags.indexOf('--app-image')+1]:undefined,resumePreparation:flags.includes('--resume-app-preparation'),resumeViewDefiner:flags.includes('--resume-view-definer-preparation'),
    prepareViewDefiner:flags.includes('--prepare-reviewed-view-definer'),hardenedImage:flags.includes('--hardened-image')?flags[flags.indexOf('--hardened-image')+1]:undefined,retainAfterPass:flags.includes('--retain-after-pass'),publishedRollback}};
}

if(process.argv[1]===fileURLToPath(import.meta.url)) {
  try {
    const {command,output,options}=parseRehearsalCli(process.argv.slice(2));
    if(command==='capture')await capture(output,options);
    else await rehearse(output,options);
  }catch(error) {console.error(JSON.stringify({result:'FAIL',code:/^[a-z_]+$/.test(error.message||'')?error.message:'mysql_upgrade_rehearsal_failed'}));process.exitCode=1;}
}
