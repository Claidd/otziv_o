import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

// The C29 Alloy adjudicator was retired with its generation. These exact-binary
// inspectors remain shared by the C43 Alloy adjudication.
const MODULE = 'github.com/docker/docker';
const DAEMON = ['github.com/docker/docker/daemon', 'github.com/moby/moby/daemon', 'github.com/moby/moby/v2/daemon'];
const isDaemon = name => DAEMON.some(prefix => name === prefix || name.startsWith(prefix + '/') || name.startsWith(prefix + '.'));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

function bounded(buffer, offset, count = 1) {
  assert.ok(Number.isSafeInteger(offset) && Number.isSafeInteger(count) && offset >= 0 &&
    count >= 0 && offset + count <= buffer.length, 'alloy_c29_elf_bounds');
  return offset;
}
function u64(buffer, offset) {
  bounded(buffer, offset, 8);
  const value = buffer.readBigUInt64LE(offset);
  assert.ok(value <= BigInt(Number.MAX_SAFE_INTEGER), 'alloy_c29_elf_integer');
  return Number(value);
}
function section(buffer, offset) {
  bounded(buffer, offset, 64);
  return { name: buffer.readUInt32LE(offset), offset: u64(buffer, offset + 24),
    size: u64(buffer, offset + 32) };
}
function nulString(buffer, start, limit) {
  bounded(buffer, start);
  assert.ok(limit <= buffer.length && start < limit, 'alloy_c29_string_bounds');
  const end = buffer.indexOf(0, start);
  assert.ok(end >= start && end < limit, 'alloy_c29_string_end');
  return buffer.toString('utf8', start, end);
}

// Go's stripped ELF still retains a complete pclntab. Examine every linked
// function, including package names, without executing the inspected binary.
export function inspectAlloyC29Functions(buffer) {
  assert.ok(Buffer.isBuffer(buffer) && buffer.subarray(0, 6).equals(Buffer.from([127, 69, 76, 70, 2, 1])),
    'alloy_c29_elf_format');
  const shoff = u64(buffer, 40), entrySize = buffer.readUInt16LE(58);
  const count = buffer.readUInt16LE(60), namesIndex = buffer.readUInt16LE(62);
  assert.ok(entrySize === 64 && count > 0 && count < 1024 && namesIndex < count, 'alloy_c29_elf_sections');
  bounded(buffer, shoff, entrySize * count);
  const sections = Array.from({ length: count }, (_, i) => section(buffer, shoff + i * entrySize));
  const names = sections[namesIndex];
  bounded(buffer, names.offset, names.size);
  const pcln = sections.filter(value => {
    assert.ok(value.name < names.size, 'alloy_c29_section_name');
    return nulString(buffer, names.offset + value.name, names.offset + names.size) === '.gopclntab';
  });
  assert.equal(pcln.length, 1, 'alloy_c29_pclntab_missing');
  const { offset: start, size } = pcln[0];
  bounded(buffer, start, size);
  assert.equal(buffer.subarray(start, start + 8).toString('hex'), 'f1ffffff00000108', 'alloy_c29_pclntab_format');
  const nfunc = u64(buffer, start + 8);
  const namesoff = u64(buffer, start + 32), cuoff = u64(buffer, start + 40);
  const fileoff = u64(buffer, start + 48), pcoff = u64(buffer, start + 56);
  const pclnoff = u64(buffer, start + 64);
  assert.ok(nfunc > 1000 && nfunc < 1_000_000 &&
    namesoff === 72 && namesoff < cuoff && cuoff < fileoff && fileoff < pcoff &&
    pcoff < pclnoff && pclnoff + (nfunc + 1) * 8 <= size, 'alloy_c29_pclntab_offsets');
  let daemonFunctions = 0, clientFunctions = 0;
  const namesHash = createHash('sha256');
  for (let i = 0; i < nfunc; i++) {
    const record = start + pclnoff + i * 8;
    const funcoff = buffer.readUInt32LE(record + 4);
    assert.ok(funcoff < size - pclnoff - 8, 'alloy_c29_function_offset');
    const nameoff = buffer.readInt32LE(start + pclnoff + funcoff + 4);
    assert.ok(nameoff >= 0, 'alloy_c29_function_name_offset');
    const name = nulString(buffer, start + namesoff + nameoff, start + cuoff);
    namesHash.update(name).update('\n');
    if (isDaemon(name)) daemonFunctions++;
    if (name.startsWith(MODULE + '/client')) clientFunctions++;
  }
  assert.equal(daemonFunctions, 0, 'alloy_c29_daemon_function_present');
  assert.ok(clientFunctions > 0, 'alloy_c29_client_function_missing');
  return { functionCount: nfunc, daemonFunctions, clientFunctions, functionNamesSha256: namesHash.digest('hex') };
}

export function inspectAlloyC29Closure(bytes) {
  const content = bytes.toString('utf8');
  assert.ok(content.endsWith('\n') && !content.includes('\r'), 'alloy_c29_closure_format');
  const packages = content.trimEnd().split('\n');
  assert.ok(packages.length > 1000 && packages.every(name => /^[a-zA-Z0-9._/+~-]+$/.test(name)),
    'alloy_c29_closure_packages');
  assert.deepEqual(packages, [...new Set(packages)].sort(), 'alloy_c29_closure_order');
  assert.ok(packages.includes('github.com/grafana/alloy/otel_engine') &&
    packages.includes(MODULE + '/client'), 'alloy_c29_closure_positive_control');
  assert.ok(!packages.some(isDaemon), 'alloy_c29_daemon_package_present');
  return { packageCount: packages.length, daemonPackages: 0, closureSha256: sha256(bytes) };
}
