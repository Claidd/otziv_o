import { formatPhoneForDisplay, phoneDigits } from './phone-format';

describe('phone format helpers', () => {
  it('formats russian phone numbers for card display', () => {
    expect(formatPhoneForDisplay('79086431055')).toBe('7-908-643-10-55');
    expect(formatPhoneForDisplay('+7 (908) 643-10-55')).toBe('7-908-643-10-55');
    expect(formatPhoneForDisplay('89086431055')).toBe('7-908-643-10-55');
  });

  it('keeps copy values as normalized digits', () => {
    expect(phoneDigits('7-908-643-10-55')).toBe('79086431055');
    expect(phoneDigits('+7 (908) 643-10-55')).toBe('79086431055');
    expect(phoneDigits('9086431055')).toBe('79086431055');
  });

  it('leaves non-standard display values readable', () => {
    expect(formatPhoneForDisplay('+7999')).toBe('+7999');
    expect(formatPhoneForDisplay()).toBe('-');
  });
});
