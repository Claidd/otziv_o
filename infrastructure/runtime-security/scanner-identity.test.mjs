import test from 'node:test';
import assert from 'node:assert/strict';
import { scannerTimeout, scannerUserArguments } from './scan.mjs';

test('the C35 publication may wait for a fresh Java DB without weakening other scans', () => {
  assert.deepEqual(scannerTimeout(), { trivy: '20m', processMs: 1_300_000 });
  assert.deepEqual(scannerTimeout(45), { trivy: '45m', processMs: 2_800_000 });
  for (const value of [0, 19, 46, '45', null]) {
    assert.throws(() => scannerTimeout(value), /scanner_timeout_invalid/);
  }
});

test('POSIX scanner and converter can use the owner of private bind directories', () => {
  assert.deepEqual(scannerUserArguments('linux', 1001, 1002), ['--user', '1001:1002']);
  assert.deepEqual(scannerUserArguments('linux', 0, 0), ['--user', '0:0']);
  assert.deepEqual(scannerUserArguments('darwin', 501, 20), ['--user', '501:20']);
});
test('POSIX identity errors fail closed and Windows keeps Docker ACL mapping', () => {
  for (const [uid, gid] of [[null, null], [-1, 1001], [1001, -1], [1.5, 1001], ['1001', 1001]]) {
    assert.throws(() => scannerUserArguments('linux', uid, gid), /scanner_local_identity_missing/);
  }
  assert.deepEqual(scannerUserArguments('win32', undefined, undefined), []);
});
