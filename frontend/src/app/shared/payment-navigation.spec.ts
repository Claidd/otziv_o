import { describe, expect, it, vi } from 'vitest';
import {
  configuredPaymentTarget,
  navigateToPaymentTarget,
  paymentTargetForUpdate,
  safePaymentNavigationTarget
} from './payment-navigation';

describe('payment navigation policy', () => {
  it.each([
    'javascript:alert(document.cookie)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'blob:https://example.test/id',
    'https://',
    '/relative/pay',
    'https://user:password@example.test/pay',
    'bankapp://user:password@pay/payment-sbp-bank',
    'bankapp://evil.example/payment-sbp-bank',
    'https://example.test/pay\r\nLocation:https://evil.test',
    'https://example.test/pay%0d%0aLocation:https://evil.test',
    'unknownbank://qr.nspk.ru/AS100000000111',
    'bank100000000111://qr.nspk.ru.evil.test/AS100000000111',
    'bank100000000111://qr.nspk.ru@evil.test/AS100000000111',
    'bank100000000111://qr.nspk.ru:443/AS100000000111',
    'bank100000000111://qr.nspk.ru\\evil.test/AS100000000111'
  ])('rejects %s without invoking navigation', (value) => {
    const navigate = vi.fn();

    expect(navigateToPaymentTarget(value, 'sbp', navigate)).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it.each([
    'https://qr.nspk.ru/AS100000000111',
    'https://www.tbank.ru/mybank/payments/qr-pay/AD100018PU4SB0748MTQ2RG6K2S26V24',
    'https://payzonaecom.com/mobile-public/goto/qr/AD10001VKP9AV8AC9CGR2FHJ7LNIUJ9C',
    'bank100000000111://qr.nspk.ru/AS100000000111',
    'bankb2b100000000111://qr.nspk.ru/AR100000000111',
    'bankapp://pay/payment-sbp-bank'
  ])('allows supported SBP target %s', (value) => {
    expect(safePaymentNavigationTarget(value, 'sbp')).toBe(value);
  });

  it('accepts an NSPK bank deep link without using the legacy browser URL parser', () => {
    const originalUrl = globalThis.URL;
    const incompatibleUrl = vi.fn(() => {
      throw new TypeError('Chrome 114 cannot expose a custom-scheme hostname');
    });
    Object.defineProperty(globalThis, 'URL', {
      configurable: true,
      value: incompatibleUrl
    });

    try {
      const target = 'bank100000000111://qr.nspk.ru/AS100000000111';
      expect(safePaymentNavigationTarget(target, 'sbp')).toBe(target);
      expect(incompatibleUrl).not.toHaveBeenCalled();
    } finally {
      Object.defineProperty(globalThis, 'URL', {
        configurable: true,
        value: originalUrl
      });
    }
  });

  it('keeps a valid public payment URL byte-for-byte unchanged', () => {
    const target = 'https://securepay.tinkoff.ru/pay?order=42#confirm';
    const navigate = vi.fn();

    expect(navigateToPaymentTarget(target, 'payment', navigate)).toBe(true);
    expect(navigate).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith(target);
  });

  it('allows current T-Bank short payment URLs from production', () => {
    const target = 'https://pay.tbank.ru/CwEMz5Cw';
    const navigate = vi.fn();

    expect(navigateToPaymentTarget(target, 'payment', navigate)).toBe(true);
    expect(navigate).toHaveBeenCalledWith(target);
  });

  it.each([
    'http://securepay.tinkoff.ru/pay',
    'https://securepay.tinkoff.ru.evil.test/pay',
    'https://evil.test/pay'
  ])('rejects an untrusted generated payment URL %s', (target) => {
    expect(safePaymentNavigationTarget(target, 'payment')).toBeNull();
  });

  it('keeps arbitrary HTTPS manual links for configured acquiring banks', () => {
    expect(safePaymentNavigationTarget('https://pay.alfabank.ru/sc/example', 'manual'))
      .toBe('https://pay.alfabank.ru/sc/example');
  });

  it('does not substitute another recipient when the backend quarantines a destination', () => {
    expect(configuredPaymentTarget('')).toBe('');
    expect(configuredPaymentTarget(null)).toBe('');
    expect(configuredPaymentTarget('  https://recipient.example/pay  ')).toBe('https://recipient.example/pay');
    expect(paymentTargetForUpdate('', false)).toBeNull();
    expect(paymentTargetForUpdate('https://new.example/pay', false)).toBe('https://new.example/pay');
  });
});
