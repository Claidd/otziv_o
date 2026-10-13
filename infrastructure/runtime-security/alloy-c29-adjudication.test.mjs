import assert from 'node:assert/strict';
import { test } from 'node:test';
import { inspectAlloyC29Closure, inspectAlloyC29Functions } from './alloy-c29-adjudication.mjs';

function elf(daemon = false) {
  const nfunc = 1001;
  const client = 'github.com/docker/docker/client.Connect';
  const other = daemon ? 'github.com/docker/docker/daemon.Daemon.containerExtractToDir' : 'runtime.goexit';
  const names = Buffer.from(client + '\0' + other + '\0');
  const shnames = Buffer.from('\0.shstrtab\0.gopclntab\0');
  const pclnStart = 128, namesoff = 72, cuoff = namesoff + names.length;
  const fileoff = cuoff + 1, pcoff = fileoff + 1, pclnoff = pcoff + 1;
  const tableBytes = (nfunc + 1) * 8, size = pclnoff + tableBytes + nfunc * 8 + 8;
  const shoff = pclnStart + size, binary = Buffer.alloc(shoff + 3 * 64);
  Buffer.from([127, 69, 76, 70, 2, 1]).copy(binary);
  binary.writeBigUInt64LE(BigInt(shoff), 40);
  binary.writeUInt16LE(64, 58);
  binary.writeUInt16LE(3, 60);
  binary.writeUInt16LE(1, 62);
  shnames.copy(binary, 64);
  const shstr = shoff + 64;
  binary.writeUInt32LE(1, shstr);
  binary.writeBigUInt64LE(64n, shstr + 24);
  binary.writeBigUInt64LE(BigInt(shnames.length), shstr + 32);
  const pcln = shoff + 2 * 64;
  binary.writeUInt32LE(11, pcln);
  binary.writeBigUInt64LE(BigInt(pclnStart), pcln + 24);
  binary.writeBigUInt64LE(BigInt(size), pcln + 32);
  Buffer.from('f1ffffff00000108', 'hex').copy(binary, pclnStart);
  for (const [i, value] of [nfunc, 1, 0, namesoff, cuoff, fileoff, pcoff, pclnoff].entries())
    binary.writeBigUInt64LE(BigInt(value), pclnStart + 8 + i * 8);
  names.copy(binary, pclnStart + namesoff);
  for (let i = 0; i < nfunc; i++) {
    const funcoff = tableBytes + i * 8;
    binary.writeUInt32LE(funcoff, pclnStart + pclnoff + i * 8 + 4);
    binary.writeInt32LE(i === 0 ? 0 : client.length + 1, pclnStart + pclnoff + funcoff + 4);
  }
  return binary;
}

function closure(daemon = false) {
  const packages = ['github.com/docker/docker/client', 'github.com/grafana/alloy/otel_engine',
    ...Array.from({ length: 1000 }, (_, i) => `example.org/pkg${String(i).padStart(4, '0')}`)];
  if (daemon) packages.push('github.com/docker/docker/daemon');
  return Buffer.from([...packages].sort().join('\n') + '\n');
}

test('exact linked Go table and import closure accept the Docker client without daemon code', () => {
  const functions = inspectAlloyC29Functions(elf());
  assert.equal(functions.functionCount, 1001);
  assert.equal(functions.clientFunctions, 1);
  assert.equal(functions.daemonFunctions, 0);
  assert.equal(inspectAlloyC29Closure(closure()).daemonPackages, 0);
});

test('daemon function, daemon import and truncated function table each reject the proof', () => {
  assert.throws(() => inspectAlloyC29Functions(elf(true)), /alloy_c29_daemon_function_present/);
  assert.throws(() => inspectAlloyC29Closure(closure(true)), /alloy_c29_daemon_package_present/);
  const truncated = elf().subarray(0, 512);
  assert.throws(() => inspectAlloyC29Functions(truncated), /alloy_c29_elf_bounds/);
});
