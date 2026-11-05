import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { shiftIsoDate, recentIsoDates, earlierIsoDateOrNull } = loadTsModule(
  'src/app/shared/audit-review-dates.ts'
);

test('lists the latest audit date and the six days before it across a month boundary', () => {
  // The module runs in its own VM realm; copy arrays before a structural comparison.
  assert.deepEqual(Array.from(recentIsoDates('2026-11-02')), [
    '2026-11-02', '2026-11-01', '2026-10-31', '2026-10-30', '2026-10-29', '2026-10-28', '2026-10-27'
  ]);
  assert.equal(recentIsoDates(null).length, 0);
  assert.equal(shiftIsoDate('2026-12-31', 1), '2027-01-01');
});

test('keeps only earlier dates; the latest date or later follows the latest audits', () => {
  assert.equal(earlierIsoDateOrNull('2026-11-01', '2026-11-05'), '2026-11-01');
  assert.equal(earlierIsoDateOrNull('2026-11-05', '2026-11-05'), null);
  assert.equal(earlierIsoDateOrNull('2026-11-06', '2026-11-05'), null);
  assert.equal(earlierIsoDateOrNull('05.11.2026', '2026-11-05'), null);
});
