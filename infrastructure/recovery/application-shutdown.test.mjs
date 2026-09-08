import test from 'node:test';
import assert from 'node:assert/strict';
import { verifyApplicationShutdown } from './application-shutdown.mjs';
const state = { pid1: 'java', exitCode: 143, running: false, oomKilled: false, elapsedMs: 20000 };
const log = ['HikariDataSource - primary - Start completed.', 'HikariDataSource - cooldown - Start completed.',
  'Graceful shutdown complete', 'Closing JPA EntityManagerFactory',
  'HikariDataSource - cooldown - Shutdown initiated...', 'HikariDataSource - cooldown - Shutdown completed.',
  'HikariDataSource - primary - Shutdown initiated...', 'HikariDataSource - primary - Shutdown completed.'].join('\n');
test('all started pools close and normal Java SIGTERM is accepted', () => {
  assert.deepEqual(verifyApplicationShutdown(state, log, 60000).poolsClosed, ['cooldown', 'primary']);
});
test('one completed pool cannot hide another missing shutdown hook or incomplete close', () => {
  for (const partial of [log.replace('HikariDataSource - primary - Shutdown completed.', ''),
    log.replace(/HikariDataSource - primary - Shutdown[^\n]*/g, '')])
    assert.throws(() => verifyApplicationShutdown(state, partial, 60000), /pool_shutdown_incomplete/);
});
test('shell PID1, force kill, OOM, timeout, running process and absent drain evidence fail', () => {
  for (const change of [{ pid1: 'sh' }, { exitCode: 137 }, { oomKilled: true }, { elapsedMs: 60000 }, { running: true }])
    assert.throws(() => verifyApplicationShutdown({ ...state, ...change }, log, 60000));
  assert.throws(() => verifyApplicationShutdown(state, log.replace('Graceful shutdown complete', ''), 60000));
  assert.throws(() => verifyApplicationShutdown(state, log.replace(/.*Start completed\.\n/g, ''), 60000));
});
