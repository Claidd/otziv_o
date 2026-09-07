import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile, open, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';

export const SYSTEM_COMPONENTS = ['mysql', 'keycloak', 'objects', 'secrets'];
const MAX_BYTES = 64 * 1024 * 1024; // This isolated fixture is deliberately bounded; not a large production backup engine.
const sha = data => createHash('sha256').update(data).digest('hex');
const signature = (value, key) => createHmac('sha256', key).update('otziv-system-manifest-v1\0').update(JSON.stringify(value)).digest('hex');
function keyCheck(key) { if (!Buffer.isBuffer(key) || key.length !== 32) throw Error('system_key_invalid'); }
function metadataCheck(meta) {
  if (meta?.production !== false || meta?.scope !== 'isolated-real-full-system-fixture' ||
      ['appStopped', 'keycloakStopped', 'objectsStopped', 'mysqlReadOnly', 'postgresReadOnly', 'writerConnectionsAbsent', 'internalNetwork'].some(name => meta?.fence?.[name] !== true) ||
      !Number.isFinite(Date.parse(meta?.captureStartedAt)) || !Number.isFinite(Date.parse(meta?.captureFinishedAt)) ||
      Date.parse(meta.captureStartedAt) > Date.parse(meta.captureFinishedAt) ||
      ['mysql', 'postgres', 'keycloak', 'app', 'objects', 'runner'].some(k => !/^sha256:[a-f0-9]{64}$/.test(meta?.images?.[k] || ''))) {
    throw Error('system_capture_evidence_invalid');
  }
}

// All four actual component archives are independently authenticated and bound
// to their manifest slot. The recovery key lives outside this bundle.
export async function sealSystemBundle(directory, files, metadata, key) {
  keyCheck(key); metadataCheck(metadata);
  await mkdir(directory, { recursive: false, mode: 0o700 });
  const components = {};
  for (const name of SYSTEM_COMPONENTS) {
    if (!files[name] || (await stat(files[name])).size > MAX_BYTES) throw Error('system_component_missing_or_too_large');
    const plaintext = await readFile(files[name]);
    if (!plaintext.length) throw Error('system_component_empty');
    const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', key, nonce);
    const aad = Buffer.from('otziv-system-component-v1\0' + name);
    cipher.setAAD(aad);
    const encrypted = Buffer.concat([nonce, cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
    const file = name + '.aesgcm';
    await writeFile(join(directory, file), encrypted, { flag: 'wx', mode: 0o600 });
    const handle = await open(join(directory, file), 'r+'); try { await handle.sync(); } finally { await handle.close(); }
    components[name] = { file, bytes: encrypted.length, sha256: sha(encrypted), plaintextSha256: sha(plaintext), plaintextBytes: plaintext.length };
    plaintext.fill(0);
  }
  const content = { schema: 'otziv-system-bundle-v1', ...metadata, components };
  const manifest = { content, hmacSha256: signature(content, key) };
  await writeFile(join(directory, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  const handle = await open(join(directory, 'manifest.json'), 'r+'); try { await handle.sync(); } finally { await handle.close(); }
  return { manifestSha256: sha(await readFile(join(directory, 'manifest.json'))), components: SYSTEM_COMPONENTS.length };
}

export async function openSystemBundle(directory, destination, key, expectedImages) {
  keyCheck(key);
  if ((await stat(join(directory, 'manifest.json'))).size > 1024 * 1024) throw Error('system_manifest_too_large');
  const manifest = JSON.parse(await readFile(join(directory, 'manifest.json'), 'utf8'));
  if (manifest?.content?.schema !== 'otziv-system-bundle-v1' || !/^[a-f0-9]{64}$/.test(manifest.hmacSha256 || '') ||
      !timingSafeEqual(Buffer.from(manifest.hmacSha256, 'hex'), Buffer.from(signature(manifest.content, key), 'hex'))) throw Error('system_manifest_authentication_failed');
  metadataCheck(manifest.content);
  if (!expectedImages || Object.keys(manifest.content.images).some(name => manifest.content.images[name] !== expectedImages[name]) ||
      Object.keys(expectedImages).length !== Object.keys(manifest.content.images).length) throw Error('system_runtime_version_mismatch');
  if (Object.keys(manifest.content.components).sort().join() !== [...SYSTEM_COMPONENTS].sort().join()) throw Error('system_components_incomplete');
  await mkdir(destination, { recursive: false, mode: 0o700 });
  const files = {};
  try {
    for (const name of SYSTEM_COMPONENTS) {
      const descriptor = manifest.content.components[name];
      if (descriptor.file !== name + '.aesgcm' || descriptor.bytes < 29 || descriptor.bytes > MAX_BYTES + 28 ||
          (await stat(join(directory, descriptor.file))).size !== descriptor.bytes) throw Error('system_component_size_or_path_invalid');
      const encrypted = await readFile(join(directory, descriptor.file));
      if (sha(encrypted) !== descriptor.sha256) throw Error('system_component_checksum_failed');
      const decipher = createDecipheriv('aes-256-gcm', key, encrypted.subarray(0, 12));
      decipher.setAAD(Buffer.from('otziv-system-component-v1\0' + name)); decipher.setAuthTag(encrypted.subarray(-16));
      let plaintext;
      try { plaintext = Buffer.concat([decipher.update(encrypted.subarray(12, -16)), decipher.final()]); }
      catch { throw Error('system_component_authentication_failed'); }
      if (plaintext.length !== descriptor.plaintextBytes || sha(plaintext) !== descriptor.plaintextSha256) throw Error('system_plaintext_checksum_failed');
      files[name] = join(destination, name + '.archive');
      await writeFile(files[name], plaintext, { flag: 'wx', mode: 0o600 }); plaintext.fill(0);
    }
    return { metadata: manifest.content, files, manifestSha256: sha(await readFile(join(directory, 'manifest.json'))) };
  } catch (error) {
    // destination was created exclusively by this call, and never contains user paths.
    await rm(destination, { recursive: true, force: true }); throw error;
  }
}
