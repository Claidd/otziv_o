import assert from 'node:assert/strict';

export function verifyApplicationShutdown({ pid1, exitCode, running, oomKilled, elapsedMs }, log, deadlineMs) {
  assert.ok(pid1 === 'java' && !running && !oomKilled && [0, 143].includes(exitCode) && elapsedMs < deadlineMs,
    'application_shutdown_forced_or_incomplete');
  assert.ok(log.includes('Graceful shutdown complete'), 'application_servlet_drain_incomplete');
  assert.ok(log.includes('Closing JPA EntityManagerFactory'), 'application_entity_manager_not_closed');
  const started = new Set([...log.matchAll(/HikariDataSource\s+-\s+(.+?) - Start completed\./g)].map(m => m[1]));
  const closing = new Set([...log.matchAll(/HikariDataSource\s+-\s+(.+?) - Shutdown initiated/g)].map(m => m[1]));
  const closed = new Set([...log.matchAll(/HikariDataSource\s+-\s+(.+?) - Shutdown completed\./g)].map(m => m[1]));
  assert.ok(started.size > 0, 'application_started_pool_evidence_missing');
  for (const pool of new Set([...started, ...closing])) assert.ok(closed.has(pool), 'application_pool_shutdown_incomplete');
  return { gracefulComplete: true, entityManagerClosed: true, poolsStarted: [...started].sort(), poolsClosed: [...closed].sort() };
}
