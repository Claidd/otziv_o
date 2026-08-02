import { BUSINESS_TIME_ZONE, businessDateIso, businessDateTimeInput, millisecondsUntilNextBusinessDay } from './business-date';

describe('business date helpers', () => {
  it('uses the configured Irkutsk business day instead of UTC', () => {
    expect(BUSINESS_TIME_ZONE).toBe('Asia/Irkutsk');
    expect(businessDateIso(new Date('2026-08-02T16:30:00.000Z'))).toBe('2026-08-03');
    expect(businessDateTimeInput(new Date('2026-08-02T16:30:00.000Z'))).toBe('2026-08-03T00:30');
  });

  it('does not roll the business date over before Irkutsk midnight', () => {
    expect(businessDateIso(new Date('2026-08-02T15:59:00.000Z'))).toBe('2026-08-02');
  });

  it('schedules the next refresh at Irkutsk midnight', () => {
    const delay = millisecondsUntilNextBusinessDay(new Date('2026-01-01T15:59:00Z'));
    expect(delay).toBeGreaterThanOrEqual(60_000);
    expect(delay).toBeLessThan(61_000);
  });
});
