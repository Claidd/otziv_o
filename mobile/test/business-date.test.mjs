import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { BUSINESS_TIME_ZONE, businessDateIso, businessDateTimeInput, millisecondsUntilNextBusinessDay } = loadTsModule(
  'src/app/shared/business-date.ts'
);

test('uses the Asia/Irkutsk business calendar around UTC midnight', () => {
  assert.equal(BUSINESS_TIME_ZONE, 'Asia/Irkutsk');
  assert.equal(businessDateIso(new Date('2026-01-01T15:59:00Z')), '2026-01-01');
  assert.equal(businessDateIso(new Date('2026-01-01T16:01:00Z')), '2026-01-02');
  assert.equal(businessDateTimeInput(new Date('2026-01-01T16:01:00Z')), '2026-01-02T00:01');
});

test('schedules refresh at the next Irkutsk calendar day', () => {
  const delay = millisecondsUntilNextBusinessDay(new Date('2026-01-01T15:59:00Z'));
  assert.ok(delay >= 60_000 && delay < 61_000);
});
