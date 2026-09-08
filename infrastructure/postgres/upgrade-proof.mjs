import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, writeFile, readdir, readFile, appendFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { Readable } from 'node:stream';
import { run, startProcess } from '../recovery/process.mjs';
import { assertLocalDocker } from '../recovery/drill.mjs';

const [candidate, outputArg] = process.argv.slice(2);
assert.match(candidate || '', /^[-a-zA-Z0-9_./:@]+$/);
const output = resolve(outputArg);
await mkdir(output, { recursive: true });
assert.equal((await readdir(output)).length, 0, 'proof_output_not_empty');
await writeFile(join(output, 'run.claim'), randomUUID(), { flag: 'wx' });
await assertLocalDocker();
const source = 'postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d';
const owner = 'otziv-pg-upgrade-' + randomUUID(), label = 'com.otziv.pg-upgrade.owner';
const containers = new Set(), volumes = new Set();
const proof = { schema: 'otziv-postgres-minor-upgrade-v1', startedAt: new Date().toISOString(), production: false,
  scope: 'Synthetic relational/JSON/index/role data; same-volume minor upgrade and separate logical-backup rollback', checks: [] };
const hash = x => createHash('sha256').update(x).digest('hex');
const docker = (args, options = {}) => run('docker', args, options);
const sql = async (name, text, database = 'fixture') => {
  const child = startProcess('docker', ['exec', '-i', name, 'psql', '-U', 'postgres', '-d', database,
    '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1']);
  let diagnostic = '';
  child.child.stderr.on('data', chunk => { diagnostic = (diagnostic + chunk).slice(-64000); });
  child.collect(); child.child.stdin.end(text);
  try { return await child.completed; }
  catch (error) { await appendFile(join(output, 'synthetic-sql-errors.log'), diagnostic); throw error; }
};
const check = (name, result) => { assert.ok(result, name); proof.checks.push({ name, passed: true }); console.log('PASS ' + name); };
async function volume(suffix) {
  const name = owner + '-' + suffix;
  await docker(['volume', 'create', '--label', `${label}=${owner}`, name]); volumes.add(name); return name;
}
async function start(suffix, image, data) {
  const name = owner + '-' + suffix;
  await docker(['create', '--name', name, '--label', `${label}=${owner}`, '--network', 'none', '--memory', '384m',
    '--pids-limit', '128', '--security-opt', 'no-new-privileges:true', '--mount', `type=volume,source=${data},target=/var/lib/postgresql/data`,
    '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=fixture', image]);
  containers.add(name); await docker(['start', name]);
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    assert.equal((await docker(['inspect', '--format', '{{.State.Running}}', name])).trim(), 'true', 'database_exited');
    // The image briefly runs an initialization server before exec'ing PID1.
    // A socket SELECT during that phase is not final service readiness.
    try {
      if ((await docker(['exec', name, 'cat', '/proc/1/comm'])).trim() === 'postgres' &&
        (await sql(name, 'SELECT 1')).trim() === '1') return name;
    } catch {}
    await new Promise(r => setTimeout(r, 500));
  }
  throw Error('postgres_readiness_timeout');
}
async function stop(name) {
  await docker(['stop', '--time', '30', name]);
  const state = JSON.parse(await docker(['inspect', '--format', '{{json .State}}', name]));
  assert.ok(!state.Running && !state.OOMKilled && state.ExitCode === 0, 'postgres_shutdown_not_clean');
}
const snapshot = name => sql(name, `SELECT jsonb_build_object(
  'rows', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM sample t),
  'view', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM sample_view t),
  'constraints', (SELECT jsonb_agg(pg_get_constraintdef(oid) ORDER BY conname) FROM pg_constraint WHERE conrelid='sample'::regclass),
  'indexes', (SELECT jsonb_agg(indexdef ORDER BY indexname) FROM pg_indexes WHERE tablename='sample'),
  'locale', (SELECT jsonb_build_object('collate',datcollate,'ctype',datctype,'provider',datlocprovider,
    'recordedVersion',datcollversion,'actualVersion',pg_database_collation_actual_version(oid)) FROM pg_database WHERE datname=current_database()),
  'encoding', current_setting('server_encoding'),
  'role', (SELECT jsonb_build_object('login',rolcanlogin,'super',rolsuper,'select',has_table_privilege('fixture_reader','sample','SELECT'),
    'insert',has_table_privilege('fixture_reader','sample','INSERT')) FROM pg_roles WHERE rolname='fixture_reader'));`);
try {
  proof.scriptSha256 = hash(await readFile(new URL(import.meta.url)));
  proof.images = {};
  for (const [key, image] of Object.entries({ source, candidate })) proof.images[key] = {
    reference: image, id: (await docker(['image', 'inspect', '--format', '{{.Id}}', image])).trim() };
  const data = await volume('data'), old = await start('old', source, data);
  proof.sourceVersion = (await sql(old, 'SHOW server_version')).trim();
  assert.match(proof.sourceVersion, /^17\.10\b/);
  await sql(old, `CREATE ROLE fixture_reader NOLOGIN;
    CREATE TABLE sample(id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, parent bigint REFERENCES sample(id),
      data jsonb NOT NULL, amount numeric(18,4) CHECK(amount>=0), stamp timestamptz NOT NULL);
    INSERT INTO sample(data,amount,stamp) SELECT jsonb_build_object('name','Тест-'||n,'enabled',n%2=0,'values',jsonb_build_array(n,null)), n/100.0,'2026-01-01T00:00:00Z'::timestamptz+n*interval '1 second' FROM generate_series(1,1000)n;
    UPDATE sample SET parent=id-1 WHERE id>1;
    CREATE INDEX sample_json ON sample USING gin(data);
    CREATE VIEW sample_view AS SELECT id,data->>'name' AS name FROM sample WHERE data@>'{"enabled":true}';
    GRANT SELECT ON sample,sample_view TO fixture_reader;`);
  const baseline = await snapshot(old);
  await writeFile(join(output, 'synthetic-baseline.json'), baseline);
  proof.baselineSha256 = hash(baseline);
  check('source_relational_json_and_index_fixture', JSON.parse(baseline).rows.length === 1000);
  // SQL archive contains synthetic data only. No account credentials are captured.
  const backup = await docker(['exec', old, 'pg_dump', '-U', 'postgres', '--no-owner', '--format=plain', 'fixture'], { maxOutput: 4 * 1024 * 1024 });
  await writeFile(join(output, 'fixture-backup.sql'), backup, { flag: 'wx', mode: 0o600 });
  proof.backup = { bytes: Buffer.byteLength(backup), sha256: hash(backup) };
  await stop(old); check('source_clean_shutdown_before_data_upgrade', true);
  const upgraded = await start('new', candidate, data);
  proof.targetVersion = (await sql(upgraded, 'SHOW server_version')).trim();
  assert.match(proof.targetVersion, /^17\.11\b/);
  check('same_volume_data_schema_locale_and_privileges_preserved', await snapshot(upgraded) === baseline);
  check('role_can_read_existing_data', (await sql(upgraded, 'SET ROLE fixture_reader; SELECT count(*) FROM sample;')).trim().endsWith('1000'));
  let denied = false;
  try { await sql(upgraded, 'SET ROLE fixture_reader; INSERT INTO sample(data,amount,stamp) VALUES(\'{}\',1,now());'); } catch { denied = true; }
  check('role_still_cannot_write', denied);
  await sql(upgraded, "INSERT INTO sample(data,amount,stamp) VALUES('{\"afterUpgrade\":true}',1,'2026-09-07T00:00:00Z');");
  const changed = await snapshot(upgraded);
  check('identity_and_json_writes_work_after_upgrade', JSON.parse(changed).rows.at(-1).id === 1001);
  await stop(upgraded);
  const restarted = await start('restart', candidate, data);
  check('target_restart_preserves_old_and_new_data', await snapshot(restarted) === changed);
  await stop(restarted);
  const rollbackData = await volume('rollback'), rollback = await start('rollback', source, rollbackData);
  await sql(rollback, 'CREATE ROLE fixture_reader NOLOGIN;');
  await sql(rollback, backup);
  check('rollback_uses_separate_fresh_volume', rollbackData !== data);
  const restored = await snapshot(rollback);
  await writeFile(join(output, 'synthetic-restored.json'), restored);
  check('original_logical_backup_restores_exact_data_and_privileges', restored === baseline);
  check('post_upgrade_mutation_absent_after_rollback', (await sql(rollback, 'SELECT count(*) FROM sample WHERE id=1001')).trim() === '0');
  await stop(rollback); proof.result = 'PASS';
} catch (error) {
  proof.result = 'FAIL'; proof.error = error.message; process.exitCode = 1;
} finally {
  try {
    for (const name of containers) {
      assert.equal((await docker(['inspect', '--format', `{{index .Config.Labels "${label}"}}`, name])).trim(), owner);
      await docker(['rm', '-f', '-v', name]);
    }
    for (const name of volumes) {
      assert.equal((await docker(['volume', 'inspect', '--format', `{{index .Labels "${label}"}}`, name])).trim(), owner);
      await docker(['volume', 'rm', name]);
    }
    proof.cleanup = 'PASS';
  } catch { proof.cleanup = 'FAIL'; proof.result = 'FAIL'; process.exitCode = 1; }
  proof.finishedAt = new Date().toISOString();
  await writeFile(join(output, 'proof.json'), JSON.stringify(proof, null, 2) + '\n');
  console.log(JSON.stringify({ result: proof.result, checks: proof.checks.length, cleanup: proof.cleanup, error: proof.error }));
}
