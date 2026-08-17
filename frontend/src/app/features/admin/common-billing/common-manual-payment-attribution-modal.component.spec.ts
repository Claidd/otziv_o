import { describe, expect, it } from 'vitest';
import {
  commonManualPaymentRublesToKopecks,
  commonManualPaymentTotalKopecks
} from './common-manual-payment-attribution-modal.component';

describe('common manual payment attribution amounts', () => {
  it('parses rubles without binary floating point rounding', () => {
    expect(commonManualPaymentRublesToKopecks('1 234,56')).toBe(123456);
    expect(commonManualPaymentRublesToKopecks('1000')).toBe(100000);
    expect(commonManualPaymentRublesToKopecks('0,01')).toBe(1);
  });

  it('rejects zero, negative and fractions below one kopeck', () => {
    expect(commonManualPaymentRublesToKopecks('0')).toBeNull();
    expect(commonManualPaymentRublesToKopecks('-1')).toBeNull();
    expect(commonManualPaymentRublesToKopecks('1,001')).toBeNull();
    expect(commonManualPaymentRublesToKopecks('руб. 10')).toBeNull();
  });

  it('requires every split row to be valid and sums exact kopecks', () => {
    expect(commonManualPaymentTotalKopecks([
      { rowKey: 'a', recipientKey: 'OWNER', amountRubles: '10,01' },
      { rowKey: 'b', recipientKey: 'PROFILE:2', amountRubles: '20,02' }
    ])).toBe(3003);
    expect(commonManualPaymentTotalKopecks([
      { rowKey: 'a', recipientKey: 'OWNER', amountRubles: '' }
    ])).toBeNull();
  });
});
