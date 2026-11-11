import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { BUSINESS_TIME_ZONE, businessDateIso, businessDateTimeInput, businessDateTimeStamp, millisecondsUntilNextBusinessDay } = loadTsModule(
  'src/app/shared/business-date.ts'
);

test('uses the Asia/Irkutsk business calendar around UTC midnight', () => {
  assert.equal(BUSINESS_TIME_ZONE, 'Asia/Irkutsk');
  assert.equal(businessDateIso(new Date('2026-01-01T15:59:00Z')), '2026-01-01');
  assert.equal(businessDateIso(new Date('2026-01-01T16:01:00Z')), '2026-01-02');
  assert.equal(businessDateTimeInput(new Date('2026-01-01T16:01:00Z')), '2026-01-02T00:01');
});

test('stamps a receipt time in the business zone whatever zone the device is set to', () => {
  // 21:44 on 10 November in New York is 10:44 on 11 November in Irkutsk.
  assert.equal(businessDateTimeStamp(new Date('2026-11-11T02:44:29.610Z')), '2026-11-11T10:44:29.610');
  assert.equal(businessDateTimeStamp(new Date('2026-08-02T16:30:05.007Z')), '2026-08-03T00:30:05.007');
});

test('the payment windows take the receipt time from the business clock, not from the device', () => {
  for (const file of [
    'src/app/shared/mobile-common-manual-payment-dialog.component.ts',
    '../frontend/src/app/features/admin/common-billing/common-manual-payment-attribution-modal.component.ts'
  ]) {
    const source = fs.readFileSync(file, 'utf8');
    assert.match(source, /effectiveAt = businessDateTimeStamp\(\)/, file);
    assert.doesNotMatch(source, /getTimezoneOffset/, file);
  }
});

test('schedules refresh at the next Irkutsk calendar day', () => {
  const delay = millisecondsUntilNextBusinessDay(new Date('2026-01-01T15:59:00Z'));
  assert.ok(delay >= 60_000 && delay < 61_000);
});
