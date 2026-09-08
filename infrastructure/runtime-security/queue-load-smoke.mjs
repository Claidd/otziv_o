import { randomUUID, createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { run } from '../recovery/process.mjs';
import { assertLocalDocker } from '../recovery/drill.mjs';

// Synthetic read/claim-plan evidence only. No app/production data or external provider.
await assertLocalDocker();
const output = resolve(process.argv[2] || '.codex-tmp/queue-load-evidence.json');
await mkdir(dirname(output), { recursive: true });
const root = new URL('../../', import.meta.url);
const source = path => readFile(new URL(path, root), 'utf8');
const compose = await source('docker-compose.yaml');
const mysqlService = compose.match(/^  mysql:\r?\n([\s\S]*?)(?=^  [a-zA-Z0-9_-]+:)/m)?.[1];
const mysqlImage = mysqlService?.match(/^    image: (mysql@sha256:[a-f0-9]{64})/m)?.[1];
if (!mysqlImage) throw new Error('pinned_mysql_image_missing');
const owner = randomUUID(), label = 'com.otziv.queue-load.owner';
const container = `otziv-queue-load-${owner}`, volume = `${container}-data`;
let volumeAllocated = false, containerAllocated = false;
const lead = await source('backend/src/main/java/com/hunt/otziv/l_lead/repository/LeadCommandRepository.java');
const performer = await source('backend/src/main/java/com/hunt/otziv/performers/repository/PerformerNotificationRepository.java');
const extract = (text, signature, replacement) => {
  const position = text.indexOf(signature);
  if (position < 0) throw new Error('query_method_missing');
  const match = text.slice(position).match(/jdbc\.(?:query|queryForObject)\("""\s*([\s\S]*?)\s*"""/);
  if (!match) throw new Error('query_text_block_missing');
  const query = match[1].replace(/\s+/g, ' ').trim().replaceAll('?', String(replacement));
  if (!query.startsWith('SELECT ')) throw new Error('fixture_only_select_plans');
  return query;
};
const queries = {
  leadClaim: extract(lead, 'Optional<Claim> claim(', 5),
  leadMetrics: extract(lead, 'List<QueueHealth> health(', 0),
  performerClaim: extract(performer, 'Optional<Intent> claim(', 0),
  performerReady: extract(performer, 'List<Long> readyAssignmentIds(', 100),
  performerMetrics: extract(performer, 'OperationalSnapshot operationalSnapshot(', 0),
};
const initialLead = await source('backend/src/main/resources/db/migration/V1_2_9__lead_sync_queue.sql');
const leadColumns = await source('backend/src/main/resources/db/migration/V1_2_91__lead_sync_queue.sql');
const leadPayload = await source('backend/src/main/resources/db/migration/V1_2_92__lead_sync_queue.sql');
const leadDelivery = await source('backend/src/main/resources/db/migration/V1_10_293__lead_command_delivery.sql');
const leadFence = await source('backend/src/main/resources/db/migration/V1_10_298__lead_command_consumer_compatibility_fence.sql');
const leadBlocking = await source('backend/src/main/resources/db/migration/V1_10_299__lead_blocking_scope_index.sql');
const performerMigration = await source('backend/src/main/resources/db/migration/V1_10_83__performers_mvp.sql');
const assignment = performerMigration.match(/CREATE TABLE IF NOT EXISTS review_performer_assignments \([\s\S]*?\n\);/)?.[0]
  .split(/\r?\n/).filter(line => !line.trim().startsWith('CONSTRAINT ')).join('\n').replace(/,\s*\);$/, '\n);');
const intentsMigration = await source('backend/src/main/resources/db/migration/V1_10_288__performer_scoped_delivery.sql');
const readinessIndex = await source('backend/src/main/resources/db/migration/V1_10_300__performer_readiness_intent_index.sql');
const intents = intentsMigration.match(/CREATE TABLE performer_notification_intents \([\s\S]*?\n\);/)?.[0];
if (!assignment || !intents) throw new Error('fixture_schema_missing');
const schema = `${initialLead}\n${leadColumns}\n${leadPayload}\n${leadDelivery}\n${leadFence}\n${leadBlocking}\n${assignment}\nALTER TABLE review_performer_assignments ADD COLUMN publication_generation BIGINT NOT NULL DEFAULT 0;\n${intents}`;
const mysql = async sql => run('docker', ['exec', '-i', container, 'mysql', '--batch', '--raw', '--skip-column-names', '--user=root', 'queue_fixture'],
  { input: Readable.from(sql), timeoutMs: 180_000, maxOutput: 4 * 1024 * 1024 });
const evidence = { schema: 'otziv-synthetic-queue-load-v1', measuredAt: new Date().toISOString(), mysqlImage,
  resources: { cpus: 2, memoryBytes: 2 * 1024 ** 3, bufferPoolBytes: 128 * 1024 ** 2, network: 'none', data: 'synthetic-only' },
  limitations: ['No network/provider or application end-to-end throughput', 'Five warm plan samples are descriptive, not an SLO',
    'Assignment FK constraints omitted only to isolate SELECT plan cost; other columns and indexes come from repository DDL',
    'No production or restored VPS tables are touched'],
  querySHA256: Object.fromEntries(Object.entries(queries).map(([name, sql]) => [name, createHash('sha256').update(sql).digest('hex')])), scenarios: [] };
try {
  await run('docker', ['volume', 'create', '--label', `${label}=${owner}`, volume]); volumeAllocated = true;
  await run('docker', ['create', '--name', container, '--label', `${label}=${owner}`, '--network', 'none',
    '--cpus', '2', '--memory', '2g', '--pids-limit', '256', '--security-opt', 'no-new-privileges:true',
    '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_DATABASE=queue_fixture',
    '--mount', `type=volume,source=${volume},target=/var/lib/mysql`, mysqlImage,
    '--innodb-buffer-pool-size=134217728', '--max-connections=20',
    '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci', '--default-time-zone=+08:00']); containerAllocated = true;
  await run('docker', ['start', container]);
  let ready = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    try { await mysql('SELECT 1;'); ready = true; break; } catch { await new Promise(resolve => setTimeout(resolve, 500)); }
  }
  if (!ready) throw new Error('fixture_mysql_not_ready');
  evidence.serverVersion = (await mysql('SELECT VERSION();')).trim();
  for (const size of [20_000, 100_000]) {
    await mysql(`DROP TABLE IF EXISTS performer_notification_intents,review_performer_assignments,lead_command_replay_audit,lead_command_queue,fixture_numbers,fixture_digits;
${schema}
CREATE TABLE fixture_digits(n INT PRIMARY KEY); INSERT INTO fixture_digits VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TABLE fixture_numbers(n INT PRIMARY KEY);
INSERT INTO fixture_numbers SELECT n FROM (SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n n FROM fixture_digits a CROSS JOIN fixture_digits b CROSS JOIN fixture_digits c CROSS JOIN fixture_digits d CROSS JOIN fixture_digits e CROSS JOIN fixture_digits f) numbers WHERE n<${size + 100};
INSERT INTO lead_command_queue (lead_id,telephone_lead,payload_json,command_id,payload_version,delivery_state,next_attempt_at,created_at)
SELECT MOD(n,1000)+1,'fixture',REPEAT('x',512),LPAD(n+1,36,'0'),1,IF(n<${size},'SUCCEEDED','READY'),TIMESTAMPADD(SECOND,n,'2020-01-01'), '2020-01-01' FROM fixture_numbers ORDER BY n;
INSERT INTO review_performer_assignments (order_id,review_id,status,publish_available_at,publication_generation)
SELECT n+1,n+1,IF(n<${size},'PAID','WAITING_PUBLICATION'),TIMESTAMPADD(SECOND,n,'2020-01-01'),1 FROM fixture_numbers ORDER BY n;
INSERT INTO performer_notification_intents (operation_key,assignment_id,notification_type,generation,status,due_at)
SELECT CONCAT('READY:',n+1,':1'),n+1,'READY',1,IF(n<${size},'SENT','PENDING'),TIMESTAMPADD(SECOND,n,'2020-01-01') FROM fixture_numbers WHERE n<${size + 50} ORDER BY n;
${readinessIndex}
ANALYZE TABLE lead_command_queue,performer_notification_intents,review_performer_assignments;`);
    for (const profile of ['distributed_terminal_history', 'hot_scope_and_already_notified_waiting']) {
      if (profile.startsWith('hot_')) await mysql(`UPDATE lead_command_queue SET lead_id=1; UPDATE review_performer_assignments SET status='WAITING_PUBLICATION' WHERE assignment_id<=${size}; ANALYZE TABLE lead_command_queue,review_performer_assignments;`);
      const scenario = { terminalRowsPerQueue: size, leadPending: 100, performerPending: 50, assignments: size + 100, profile, measurements: {} };
      for (const [name, query] of Object.entries(queries)) {
        const plans = [];
        for (let sample = 0; sample < 6; sample++) plans.push(await mysql(`SET SESSION max_execution_time=10000; START TRANSACTION; EXPLAIN ANALYZE ${query}; ROLLBACK;`));
        const times = plans.map(plan => Number(plan.match(/actual time=[\d.]+\.\.([\d.]+)/)?.[1]));
        if (times.some(value => !Number.isFinite(value))) throw new Error('mysql_actual_timing_missing');
        const warm = times.slice(1).sort((a,b) => a-b);
        scenario.measurements[name] = { firstMs: times[0], warmMedianMs: warm[2], warmMaxMs: warm[4], samples: times, plan: plans[5].trim() };
      }
      evidence.scenarios.push(scenario);
      await writeFile(output, JSON.stringify(evidence, null, 2) + '\n');
      console.log(JSON.stringify({ rows: size, profile, warmMedianMs: Object.fromEntries(Object.entries(scenario.measurements).map(([name,m]) => [name,m.warmMedianMs])) }));
    }
  }
  evidence.result = 'PASS';
} finally {
  if (containerAllocated) {
    if ((await run('docker', ['inspect', '--format', `{{index .Config.Labels "${label}"}}`, container])).trim() !== owner) throw new Error('fixture_container_owner_mismatch');
    await run('docker', ['rm', '--force', container]);
  }
  if (volumeAllocated) {
    if ((await run('docker', ['volume', 'inspect', '--format', `{{index .Labels "${label}"}}`, volume])).trim() !== owner) throw new Error('fixture_volume_owner_mismatch');
    await run('docker', ['volume', 'rm', volume]);
  }
  evidence.cleanup = 'OWNED_RESOURCES_REMOVED';
  await writeFile(output, JSON.stringify(evidence, null, 2) + '\n');
}
