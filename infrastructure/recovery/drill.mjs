import { createReadStream } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { stat } from 'node:fs/promises';
import { decryptArchive, encryptionKey, sha256File } from './envelope.mjs';
import { workspace } from './backup.mjs';
import { verifyManifest } from './manifest.mjs';
import { run } from './process.mjs';

export async function assertLocalDocker(env = process.env, execute = run) {
  if (env.DOCKER_HOST && !/^(unix|npipe):\/\//.test(env.DOCKER_HOST)) throw new Error('remote_docker_forbidden');
  const endpoint = (await execute('docker', ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'])).trim();
  if (!/^(unix|npipe):\/\/[^\r\n]+$/.test(endpoint)) throw new Error('remote_docker_forbidden');
}

export async function postgresDrill(config, manifest, archive, env = process.env, execute = run) {
  verifyManifest(manifest);
  if (!/^postgres(?::[^@]+)?@sha256:[a-f0-9]{64}$/.test(config.postgresImage || '')) throw new Error('postgres_image_not_pinned');
  if (manifest.postgresImage !== config.postgresImage || manifest.postgresMajor !== config.postgres.major ||
      manifest.keycloakVersion !== config.keycloakVersion || manifest.databases.postgres.keyId !== config.keyId) {
    throw new Error('restore_version_or_key_identity_mismatch');
  }
  const expected = manifest.databases.postgres;
  if ((await stat(archive)).size !== expected.bytes || await sha256File(archive) !== expected.sha256) throw new Error('restore_object_checksum_mismatch');
  const startedAt = Date.now();
  const key = encryptionKey(env.RECOVERY_ENCRYPTION_KEY_BASE64);
  const local = await workspace(config.workDirectory);
  const owner = randomUUID();
  const name = `otziv-pg-drill-${owner}`;
  const volume = `${name}-data`;
  const label = 'com.otziv.pg-drill.owner';
  let dockerChecked = false;
  let volumeAttempted = false;
  let containerAttempted = false;
  let passed = false;
  try {
    const dump = join(local.path, 'authenticated.dump');
    // No Docker resource or process sees plaintext until GCM authentication finishes.
    await decryptArchive(archive, key, { destination: dump, maxBytes: config.maxDumpBytes });
    await assertLocalDocker(env, execute);
    dockerChecked = true;
    await execute('docker', ['image', 'inspect', config.postgresImage]);
    const collision = await execute('docker', ['container', 'ls', '-a', '--filter', `name=^/${name}$`, '--format', '{{.Names}}']);
    const volumeCollision = await execute('docker', ['volume', 'ls', '--filter', `name=^${volume}$`, '--format', '{{.Name}}']);
    if (collision.trim() || volumeCollision.trim()) throw new Error('drill_resource_collision');
    volumeAttempted = true;
    await execute('docker', ['volume', 'create', '--label', `${label}=${owner}`, volume]);
    containerAttempted = true;
    await execute('docker', ['run', '--detach', '--pull=never', '--name', name,
      '--label', `${label}=${owner}`, '--network', 'none', '--memory', '2g', '--cpus', '2', '--pids-limit', '128',
      '--security-opt', 'no-new-privileges:true', '--env', 'POSTGRES_USER=keycloak', '--env', 'POSTGRES_DB=keycloak',
      '--env', 'POSTGRES_HOST_AUTH_METHOD=trust', '--mount', `type=volume,source=${volume},target=/var/lib/postgresql/data`,
      config.postgresImage]);
    const deadline = Date.now() + 180_000;
    for (;;) {
      try { await execute('docker', ['exec', name, 'pg_isready', '-U', 'keycloak', '-d', 'keycloak']); break; }
      catch { if (Date.now() >= deadline) throw new Error('postgres_drill_startup_timeout'); }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    await execute('docker', ['exec', '-i', name, 'pg_restore', '--exit-on-error', '--no-owner', '--no-acl',
      '-U', 'keycloak', '-d', 'keycloak'], { input: createReadStream(dump), timeoutMs: config.rtoSeconds * 1000 });
    const sql = "SELECT (to_regclass('public.user_entity') IS NOT NULL AND to_regclass('public.realm') IS NOT NULL AND to_regclass('public.user_role_mapping') IS NOT NULL AND to_regclass('public.databasechangelog') IS NOT NULL); SELECT count(*) > 0 FROM public.realm; SELECT count(*) > 0 FROM public.databasechangelog;";
    const checks = (await execute('docker', ['exec', name, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'keycloak', '-d', 'keycloak', '-Atc', sql])).trim().split(/\r?\n/);
    if (checks.length !== 3 || checks.some(value => value !== 't')) throw new Error('keycloak_schema_verification_failed');
    passed = true;
  } finally {
    key.fill(0);
    const failures = [];
    if (dockerChecked) {
      for (const [kind, resource, attempted] of [['container', name, containerAttempted], ['volume', volume, volumeAttempted]]) {
        if (!attempted) continue;
        try {
          const ownership = (await execute('docker', [kind, 'inspect', '--format', `{{index .${kind === 'container' ? 'Config.' : ''}Labels "${label}"}}`, resource])).trim();
          if (ownership !== owner) throw new Error('drill_cleanup_ownership_mismatch');
          await execute('docker', [kind, 'rm', ...(kind === 'container' ? ['--force'] : []), resource]);
        } catch { failures.push(kind); }
      }
    }
    try { await local.cleanup(); } catch { failures.push('local'); }
    if (failures.length) throw new Error('drill_cleanup_failed');
  }
  const elapsedSeconds = Math.ceil((Date.now() - startedAt) / 1000);
  return { schema: 'otziv-postgres-restore-drill-v1', result: passed ? 'PASS' : 'FAIL',
    cleanup: 'PASS', elapsedSeconds, withinConfiguredRto: elapsedSeconds <= config.rtoSeconds,
    fullSystemRestore: 'NOT_PROVEN', manifestSha256: manifest.contentSha256 };
}
