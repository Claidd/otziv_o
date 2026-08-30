import { describe, expect, it } from 'vitest';
import {
  bankPaymentRouteLabel,
  bankProfileOptionLabel,
  bankProviderLabel,
  isBankPaymentRouteType
} from './bank-payment-presentation';

describe('bank payment presentation', () => {
  it('shows provider-neutral profile choices with a clear bank name', () => {
    expect(bankProfileOptionLabel({ name: 'Основной магазин', provider: 'T_BANK', enabled: true, hasPassword: true }))
      .toBe('T‑Bank · Основной магазин');
    expect(bankProfileOptionLabel({ name: 'Точка', provider: 'TOCHKA', enabled: false, hasPassword: true }))
      .toBe('Точка Банк · Точка · выключен');
    expect(bankProfileOptionLabel({ name: 'Резерв', provider: 'TBANK', enabled: true, hasPassword: false }))
      .toBe('T‑Bank · Резерв · нет реквизитов');
    expect(bankProfileOptionLabel({ name: 'Точка', provider: 'TOCHKA', enabled: true, operational: false, hasPassword: true }))
      .toBe('Точка Банк · Точка · не готов');
  });

  it('keeps legacy and accepts provider-neutral bank routes', () => {
    expect(isBankPaymentRouteType('TBANK_LINK')).toBe(true);
    expect(isBankPaymentRouteType('TOCHKA_LINK')).toBe(true);
    expect(isBankPaymentRouteType('BANK_LINK')).toBe(true);
    expect(isBankPaymentRouteType('MANAGER_TEXT')).toBe(false);
  });

  it('uses the provider when available and infers it for legacy routes', () => {
    expect(bankProviderLabel('TOCHKA')).toBe('Точка Банк');
    expect(bankPaymentRouteLabel('TBANK_LINK', 'Основной')).toBe('T‑Bank · Основной');
    expect(bankPaymentRouteLabel('BANK_LINK', 'Новый', 'TOCHKA')).toBe('Точка Банк · Новый');
  });
});
