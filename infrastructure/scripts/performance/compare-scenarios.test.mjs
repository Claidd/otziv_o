import test from 'node:test';
import assert from 'node:assert/strict';
import { compareScenarios } from './compare-scenarios.mjs';

function report(sourceLabel, queries = 10) {
  return { schema: 'otziv-scenario-benchmark-v1', fixture: 'fixture', mysqlImage: 'pinned', jdbcPoolSize: 8,
    harnessSha256: 'a'.repeat(64), sourceLabel, explain: [{ sql: 'select 1', plan: '{}' }],
    results: ['common-invoice-board', 'payment-admin-board', 'manager-control-details'].flatMap(scenario =>
      [1, 4, 8].map(concurrency => ({ scenario, concurrency, samples: 24 * concurrency, rollbacks: 0,
        queriesMin: queries, queriesMax: queries, p50Ms: 10, p95Ms: 20, completedPerSecond: 30 }))),
  };
}

test('compares each scenario with its actual pre-decomposition source', () => {
  const rows = compareScenarios(report('before-finance', 12), report('before-manager', 14), report('after', 10));
  assert.equal(rows.length, 9);
  assert.equal(rows[0].queryChange, -2);
  assert.equal(rows[6].queryChange, -4);
  assert.equal(rows[6].beforeSource, 'before-manager');
});

test('rejects changed harness, database or dataset rather than publishing misleading deltas', () => {
  for (const key of ['fixture', 'mysqlImage', 'jdbcPoolSize', 'harnessSha256']) {
    const changed = report('before'); changed[key] = 'different';
    assert.throws(() => compareScenarios(changed, report('manager'), report('after')), /Incomparable/);
  }
});

test('rejects pilots, duplicate rows and failed database requests', () => {
  for (const mutate of [r => r.results[0].samples = 2, r => r.results[1] = r.results[0], r => r.results[0].rollbacks = 1]) {
    const changed = report('before'); mutate(changed);
    assert.throws(() => compareScenarios(changed, report('manager'), report('after')));
  }
});
