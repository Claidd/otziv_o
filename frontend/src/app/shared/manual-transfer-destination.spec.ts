import { describe, expect, it } from 'vitest';
import {
  isManualTransferCard,
  manualTransferDestinationPresentation
} from './manual-transfer-destination';

describe('manual transfer destination presentation', () => {
  it.each([
    '2202208238396676',
    '2202 2082 3839 6676',
    '1234-5678-9012-3456-789',
    '2202 (2082) 3839—6676'
  ])('shows card copy for a 16 to 19 digit card: %s', (value) => {
    expect(isManualTransferCard(value)).toBe(true);
    expect(manualTransferDestinationPresentation(value)).toEqual({
      kind: 'CARD',
      fieldLabel: 'Номер карты',
      paymentTitle: 'Оплата по номеру карты'
    });
  });

  it.each([
    '+7 (999) 123-45-67',
    '123456789012345',
    '12345678901234567890',
    '+1234567890123456',
    null,
    undefined
  ])('keeps mobile-bank copy for a value that is not a card: %s', (value) => {
    expect(isManualTransferCard(value)).toBe(false);
    expect(manualTransferDestinationPresentation(value)).toEqual({
      kind: 'PHONE',
      fieldLabel: 'Номер телефона',
      paymentTitle: 'Оплата через мобильный банк'
    });
  });
});
