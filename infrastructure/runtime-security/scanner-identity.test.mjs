import test from 'node:test';
import assert from 'node:assert/strict';
import { scannerUserArguments } from './scan.mjs';

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
