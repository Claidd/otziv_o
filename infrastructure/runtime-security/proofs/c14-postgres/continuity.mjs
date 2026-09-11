import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { mkdir, readdir, readFile, writeFile, appendFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { run, startProcess } from '../../../recovery/process.mjs';
import { assertLocalDocker } from '../../../recovery/drill.mjs';

const [candidate, outputArg] = process.argv.slice(2);
assert.match(candidate || '', /^[-a-zA-Z0-9_./:@]+$/);
assert.ok(outputArg, 'output_required');
const output = resolve(outputArg);
await mkdir(output, { recursive: true });
assert.equal((await readdir(output)).length, 0, 'proof_output_not_empty');
await assertLocalDocker();
const owner = 'otziv-c14-postgres-' + randomUUID();
const label = 'com.otziv.c14-postgres.owner';
const source = 'postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d';
const hash = x => createHash('sha256').update(x).digest('hex');
const containers = new Set(), volumes = new Set();
const docker = (args, options={}) => run('docker', args, options);
const proof = { schema: 'otziv-c14-postgres-continuity-v1', owner, production: false,
  startedAt: new Date().toISOString(), scope: 'Synthetic data only; no source stack or existing volume access',
  checks: [], failures: [], resources: { cpu: 1, memoryMiB: 512, network: 'none', publishedPorts: 0 } };
const check = (name, value) => { assert.ok(value, name); proof.checks.push({ name, passed: true }); console.log('PASS ' + name); };
const sql = async (name, statement, database='fixture') => {
  const p = startProcess('docker', ['exec','-i',name,'psql','-U','postgres','-d',database,'-X','-A','-t','-v','ON_ERROR_STOP=1']);
  let diagnostic='';
  p.child.stderr.on('data', x => { diagnostic=(diagnostic+x.toString('utf8')).slice(-64000); });
  p.collect(); p.child.stdin.end(statement);
  try { return await p.completed; }
  catch (e) { await appendFile(join(output,'sql-diagnostics.log'),diagnostic); throw e; }
};
async function volume(suffix) {
  const name=owner+'-'+suffix;
  await docker(['volume','create','--label',`${label}=${owner}`,name]); volumes.add(name); return name;
}
async function start(suffix, image, data, initializers) {
  const name=owner+'-'+suffix;
  for (const current of containers) {
    const state=JSON.parse(await docker(['inspect','--format','{{json .}}',current]));
    assert.ok(!state.State.Running || !state.Mounts.some(m=>m.Name===data),'two_database_writers');
  }
  await docker(['create','--name',name,'--label',`${label}=${owner}`,'--network','none','--memory','512m','--cpus','1',
    '--pids-limit','128','--security-opt','no-new-privileges:true','--mount',`type=volume,source=${data},target=/var/lib/postgresql/data`,
    ...(initializers?['--mount',`type=bind,source=${initializers},target=/docker-entrypoint-initdb.d,readonly`]:[]),
    '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=fixture',image]);
  containers.add(name); await docker(['start',name]);
  const deadline=Date.now()+120000;
  while(Date.now()<deadline) {
    const running=(await docker(['inspect','--format','{{.State.Running}}',name])).trim()==='true';
    if (!running) {
      await writeFile(join(output,suffix+'-container.log'),await docker(['logs',name]).catch(()=>''));
      throw Error('database_exited:'+suffix);
    }
    try {
      if ((await docker(['exec',name,'cat','/proc/1/comm'])).trim()==='postgres' && (await sql(name,'SELECT 1')).trim()==='1') return name;
    } catch {}
    await new Promise(r=>setTimeout(r,250));
  }
  throw Error('database_readiness_timeout:'+suffix);
}
async function stop(name) {
  await docker(['stop','--time','30',name]);
  const state=JSON.parse(await docker(['inspect','--format','{{json .State}}',name]));
  assert.ok(!state.Running && !state.OOMKilled && state.ExitCode===0,'database_clean_shutdown');
}
const snapshot = name => sql(name, `SELECT jsonb_build_object(
  'rows',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM sample t),
  'view',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM sample_view t),
  'xml',(SELECT jsonb_agg(jsonb_build_object('id',id,'value',xmlserialize(DOCUMENT payload AS text)) ORDER BY id) FROM xml_values),
  'constraints',(SELECT jsonb_agg(pg_get_constraintdef(oid) ORDER BY conname) FROM pg_constraint WHERE conrelid='sample'::regclass),
  'indexes',(SELECT jsonb_agg(indexdef ORDER BY indexname) FROM pg_indexes WHERE tablename IN ('sample','names')),
  'role',(SELECT jsonb_build_object('login',rolcanlogin,'super',rolsuper,'select',has_table_privilege('fixture_reader','sample','SELECT'),
    'insert',has_table_privilege('fixture_reader','sample','INSERT')) FROM pg_roles WHERE rolname='fixture_reader'))`);
const dump = name => docker(['exec',name,'pg_dump','-U','postgres','--no-owner','--format=plain','fixture'],{maxOutput:8*1024*1024});
const extensions = name => sql(name,"SELECT jsonb_agg(name ORDER BY name) FROM pg_available_extensions");
const unicodeSql=await readFile(new URL('./unicode-controls.sql',import.meta.url),'utf8');
try {
  proof.scriptSha256=hash(await readFile(new URL(import.meta.url)));
  proof.unicodeScriptSha256=hash(unicodeSql);
  proof.images={};
  for(const [name,image] of Object.entries({source,candidate})) {
    const inspected=JSON.parse(await docker(['image','inspect',image]))[0];
    assert.equal(inspected.Os,'linux'); assert.equal(inspected.Architecture,'amd64');
    proof.images[name]={reference:image,id:inspected.Id,repoDigests:inspected.RepoDigests};
  }
  const data=await volume('data'), old=await start('old',source,data);
  proof.sourceVersion=(await sql(old,'SHOW server_version')).trim();
  assert.match(proof.sourceVersion,/^17\.10\b/);
  const oldExtensions=await extensions(old);
  await sql(old,`CREATE ROLE fixture_reader NOLOGIN;
    CREATE TABLE sample(id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,parent bigint REFERENCES sample(id),
      data jsonb NOT NULL,amount numeric(18,4) CHECK(amount>=0),stamp timestamptz NOT NULL);
    INSERT INTO sample(data,amount,stamp) SELECT jsonb_build_object('name','Тест-'||n,'enabled',n%2=0,'values',jsonb_build_array(n,null)),
      n/100.0,'2026-01-01T00:00:00Z'::timestamptz+n*interval '1 second' FROM generate_series(1,1000)n;
    UPDATE sample SET parent=id-1 WHERE id>1;
    CREATE INDEX sample_json ON sample USING gin(data);
    CREATE VIEW sample_view AS SELECT id,data->>'name' AS name FROM sample WHERE data@>'{"enabled":true}';
    CREATE TABLE names(value text NOT NULL);
    CREATE UNIQUE INDEX names_casefold ON names(lower(value));
    INSERT INTO names VALUES('Alice'),('Straße'),('STRASSE'),('ё'),('е');
    CREATE TABLE xml_values(id int PRIMARY KEY,payload xml NOT NULL);
    INSERT INTO xml_values VALUES(1,XMLPARSE(DOCUMENT '<root><item id="1">Тест &amp; café</item><item id="2">ß</item></root>'));
    GRANT SELECT ON sample,sample_view TO fixture_reader;`);
  const baseline=await snapshot(old), locale=await sql(old,unicodeSql);
  await writeFile(join(output,'baseline.json'),baseline);
  await writeFile(join(output,'source-unicode.json'),locale);
  check('source_fixture_has_1000_relational_json_rows',JSON.parse(baseline).rows.length===1000);
  check('source_locale_is_utf8_libc_en_us_2_41',(()=>{const x=JSON.parse(locale);return x.encoding==='UTF8'&&x.locale.provider==='c'&&x.locale.collate==='en_US.utf8'&&x.locale.actualVersion==='2.41';})());
  const backup=await dump(old);
  await writeFile(join(output,'pre-upgrade.sql'),backup,{flag:'wx'});
  proof.preUpgradeBackupSha256=hash(backup);
  await stop(old); check('clean_stop_before_exclusive_same_volume_upgrade',true);
  const upgraded=await start('candidate',candidate,data);
  proof.candidateVersion=(await sql(upgraded,'SHOW server_version')).trim();
  assert.match(proof.candidateVersion,/^17\.11\b/);
  check('same_volume_data_schema_privileges_and_xml_preserved',await snapshot(upgraded)===baseline);
  check('all_original_available_extensions_preserved',await extensions(upgraded)===oldExtensions);
  const candidateLocale=await sql(upgraded,unicodeSql);
  await writeFile(join(output,'candidate-unicode.json'),candidateLocale);
  check('all_41_unicode_order_case_and_820_equality_controls_identical',candidateLocale===locale);
  check('llvm_jit_explicitly_unavailable',(await sql(upgraded,'SELECT pg_jit_available()')).trim()==='f');
  check('select_grant_preserved',(await sql(upgraded,'SET ROLE fixture_reader; SELECT count(*) FROM sample')).trim().endsWith('1000'));
  let denied=false;try{await sql(upgraded,"SET ROLE fixture_reader; INSERT INTO sample(data,amount,stamp) VALUES('{}',1,now())");}catch{denied=true;}
  check('write_denied_for_read_only_role',denied);
  let duplicate=false;try{await sql(upgraded,"INSERT INTO names VALUES('ALICE')");}catch{duplicate=true;}
  check('casefold_unique_index_still_rejects_existing_identity',duplicate);
  check('ordinary_indexed_json_query_unchanged',(await sql(upgraded,"SELECT count(*) FROM sample WHERE data@>'{\"enabled\":true}'")).trim()==='500');
  check('sql_xml_xpath_and_utf8_content_work',(await sql(upgraded,"SELECT (xpath('/root/item[@id=\"1\"]/text()',payload))[1]::text FROM xml_values WHERE id=1")).trim()==='Тест &amp; café');
  let malformed=false;try{await sql(upgraded,"SELECT XMLPARSE(DOCUMENT '<root><bad></root>')");}catch{malformed=true;}
  check('malformed_xml_is_rejected_and_server_survives',malformed&&(await sql(upgraded,'SELECT 1')).trim()==='1');
  await sql(upgraded,"INSERT INTO sample(data,amount,stamp) VALUES('{\"afterUpgrade\":true}',1,'2026-09-08T00:00:00Z'); CREATE EXTENSION xml2; SELECT xpath_string('<a><b>ok</b></a>','/a/b');");
  const changed=await snapshot(upgraded), afterBackup=await dump(upgraded);
  await writeFile(join(output,'post-upgrade.sql'),afterBackup,{flag:'wx'});
  proof.postUpgradeBackupSha256=hash(afterBackup);
  check('new_identity_and_json_writes_work',JSON.parse(changed).rows.at(-1).id===1001);
  await stop(upgraded);
  const restarted=await start('restart',candidate,data);
  check('candidate_restart_preserves_old_and_new_rows',await snapshot(restarted)===changed);
  check('restart_preserves_collation_and_case_controls',await sql(restarted,unicodeSql)===locale);
  await stop(restarted);
  const initializers=join(output,'initializers');await mkdir(initializers);
  await writeFile(join(initializers,'01-fixture.sql.gz'),gzipSync("CREATE TABLE initialized(value text); INSERT INTO initialized VALUES('Тест & café');"));
  const initializedData=await volume('fresh-init'),initialized=await start('fresh-init',candidate,initializedData,initializers);
  check('unchanged_official_entrypoint_initializes_fresh_cluster_and_gzip_sql',
    (await sql(initialized,'SELECT value FROM initialized')).trim()==='Тест & café');
  await stop(initialized);
  const rollbackData=await volume('rollback-before'), rollback=await start('rollback-before',source,rollbackData);
  check('rollback_uses_separate_fresh_volume',rollbackData!==data);
  await sql(rollback,'CREATE ROLE fixture_reader NOLOGIN;');await sql(rollback,backup);
  check('original_backup_restores_exact_baseline',await snapshot(rollback)===baseline);
  await stop(rollback);
  const newBackupData=await volume('rollback-after'), rollbackAfter=await start('rollback-after',source,newBackupData);
  await sql(rollbackAfter,'CREATE ROLE fixture_reader NOLOGIN;');await sql(rollbackAfter,afterBackup);
  check('new_candidate_backup_restores_to_original_17_10',await snapshot(rollbackAfter)===changed);
  check('logical_rollback_keeps_post_upgrade_write_and_original_case_semantics',
    (await sql(rollbackAfter,'SELECT count(*) FROM sample WHERE id=1001')).trim()==='1' && await sql(rollbackAfter,unicodeSql)===locale);
  await stop(rollbackAfter);proof.result='PASS';
} catch(error) {proof.result='FAIL';proof.failures.push(error.message);process.exitCode=1;}
finally {
  try {
    for(const name of containers) {
      assert.equal((await docker(['inspect','--format',`{{index .Config.Labels "${label}"}}`,name])).trim(),owner);
      await docker(['rm','-f','-v',name]);
    }
    for(const name of volumes) {
      assert.equal((await docker(['volume','inspect','--format',`{{index .Labels "${label}"}}`,name])).trim(),owner);
      await docker(['volume','rm',name]);
    }
    proof.cleanup='PASS';
  } catch(error) {proof.cleanup='FAIL';proof.result='FAIL';proof.failures.push(error.message);process.exitCode=1;}
  proof.finishedAt=new Date().toISOString();
  await writeFile(join(output,'proof.json'),JSON.stringify(proof,null,2)+'\n');
  console.log(JSON.stringify({result:proof.result,checks:proof.checks.length,cleanup:proof.cleanup,failures:proof.failures}));
}
