import { describe, expect, it } from 'vitest';
import {
  validateContractorTransferIdentifier,
  validateContractorTransferIdentifierForSave
} from './contractor-transfer-identifier';

describe('contractor transfer identifier', () => {
  it.each([
    '+7 (999) 123-45-67',
    '8 999 123 45 67',
    '123456789012345',
    '8‒999‒123‒45‒67'
  ])('accepts a phone with 10 to 15 digits: %s', (value) => {
    const result = validateContractorTransferIdentifier(value, true);

    expect(result.valid).toBe(true);
    expect(result.kind).toBe('PHONE');
    expect(result.normalizedValue).toMatch(/^\+?\d{10,15}$/);
  });

  it.each([
    '2202208238396676',
    '2202 2082 3839 6676',
    '1234-5678-9012-3456-789',
    '2202 (2082) 3839—6676'
  ])('accepts a card with 16 to 19 digits: %s', (value) => {
    const result = validateContractorTransferIdentifier(value, true);

    expect(result.valid).toBe(true);
    expect(result.kind).toBe('CARD');
    expect(result.normalizedValue).toMatch(/^\d{16,19}$/);
  });

  it.each([
    '123456789',
    '12345678901234567890',
    '+1234567890123456',
    '2202 2082 card 6676'
  ])('rejects an invalid phone or card: %s', (value) => {
    const result = validateContractorTransferIdentifier(value, true);

    expect(result.valid).toBe(false);
    expect(result.error).toContain('10–15');
    expect(result.error).toContain('16–19');
  });

  it('allows an empty optional value but explains a required one', () => {
    expect(validateContractorTransferIdentifier('   ').valid).toBe(true);

    const required = validateContractorTransferIdentifier('   ', true);
    expect(required.valid).toBe(false);
    expect(required.error).toBe('Укажите номер телефона или карты получателя.');
  });

  it('preserves an invalid historical value when profile eligibility is not being enabled', () => {
    const historicalValue = '  старый реквизит  ';

    expect(validateContractorTransferIdentifierForSave(historicalValue, false)).toEqual({
      valid: true,
      kind: 'PHONE',
      normalizedValue: historicalValue,
      error: null
    });
    expect(validateContractorTransferIdentifierForSave(historicalValue, true).valid).toBe(false);
  });
});
