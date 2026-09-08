import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { open, stat, unlink } from 'node:fs/promises';
import { Transform, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const MAGIC = Buffer.from('OTZIVPG1');
const HEADER_BYTES = 20;
export const MAX_DUMP_BYTES = 32 * 1024 ** 3; // Below the single-message GCM limit.

export function encryptionKey(value) {
  if (!/^[A-Za-z0-9+/]{43}=$/.test(value || '')) throw new Error('encryption_key_invalid');
  const key = Buffer.from(value, 'base64');
  if (key.length !== 32) throw new Error('encryption_key_invalid');
  return key;
}

function boundedCounter(maxBytes) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 5 || maxBytes > MAX_DUMP_BYTES) {
    throw new Error('dump_limit_invalid');
  }
  let bytes = 0;
  let prefix = Buffer.alloc(0);
  return new Transform({ transform(chunk, encoding, callback) {
    bytes += chunk.length;
    if (prefix.length < 5) prefix = Buffer.concat([prefix, chunk.subarray(0, 5 - prefix.length)]);
    if (bytes > maxBytes) return callback(new Error('dump_limit_exceeded'));
    callback(null, chunk);
  }, final(callback) {
    callback(prefix.equals(Buffer.from('PGDMP')) ? null : new Error('postgres_archive_invalid'));
  } });
}

export async function sha256File(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}

export async function syncFile(path) {
  const handle = await open(path, 'r+');
  try { await handle.sync(); } finally { await handle.close(); }
}

// pg_dump stdout flows straight into authenticated encryption; no plaintext backup file.
export async function encryptArchive(source, destination, key, maxBytes = MAX_DUMP_BYTES) {
  const header = Buffer.concat([MAGIC, randomBytes(12)]);
  const cipher = createCipheriv('aes-256-gcm', key, header.subarray(8));
  cipher.setAAD(header);
  const handle = await open(destination, 'wx', 0o600);
  try { await handle.writeFile(header); } finally { await handle.close(); }
  try {
    await pipeline(source, boundedCounter(maxBytes), cipher,
      createWriteStream(destination, { flags: 'a' }));
    const encrypted = await open(destination, 'r+');
    try { await encrypted.write(cipher.getAuthTag(), 0, 16, (await encrypted.stat()).size); }
    finally { await encrypted.close(); }
    await syncFile(destination);
  } catch (error) {
    await unlink(destination);
    throw error;
  }
}

export async function decryptArchive(source, key, { destination, maxBytes = MAX_DUMP_BYTES } = {}) {
  const size = (await stat(source)).size;
  if (size < HEADER_BYTES + 16 + 5 || size > maxBytes + HEADER_BYTES + 16) {
    throw new Error('encrypted_archive_size_invalid');
  }
  const handle = await open(source, 'r');
  const header = Buffer.alloc(HEADER_BYTES);
  const tag = Buffer.alloc(16);
  try {
    await handle.read(header, 0, header.length, 0);
    await handle.read(tag, 0, tag.length, size - 16);
  } finally { await handle.close(); }
  if (!header.subarray(0, 8).equals(MAGIC)) throw new Error('encrypted_archive_format_invalid');
  const decipher = createDecipheriv('aes-256-gcm', key, header.subarray(8));
  decipher.setAAD(header);
  decipher.setAuthTag(tag);
  let allocated = false;
  try {
    if (destination) {
      const output = await open(destination, 'wx', 0o600);
      await output.close();
      allocated = true;
    }
    await pipeline(createReadStream(source, { start: HEADER_BYTES, end: size - 17 }),
      decipher, boundedCounter(maxBytes), destination
        ? createWriteStream(destination, { flags: 'r+' })
        : new Writable({ write(chunk, encoding, callback) { callback(); } }));
    if (destination) await syncFile(destination);
    return { plaintextBytes: size - HEADER_BYTES - 16 };
  } catch {
    if (allocated) await unlink(destination);
    throw new Error('encrypted_archive_authentication_failed');
  }
}
