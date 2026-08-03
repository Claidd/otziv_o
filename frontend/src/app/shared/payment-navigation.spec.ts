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
    'unknownbank://qr.nspk.ru/AS100000000111'
  ])('rejects %s without invoking navigation', (value) => {
    const navigate = vi.fn();

    expect(navigateToPaymentTarget(value, 'sbp', navigate)).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it.each([
    'https://qr.nspk.ru/AS100000000111',
    'bank100000000111://qr.nspk.ru/AS100000000111',
    'bankb2b100000000111://qr.nspk.ru/AR100000000111',
    'bankapp://pay/payment-sbp-bank'
  ])('allows supported SBP target %s', (value) => {
    expect(safePaymentNavigationTarget(value, 'sbp')).toBe(value);
  });

  it('keeps a valid public payment URL byte-for-byte unchanged', () => {
    const target = 'https://securepay.tinkoff.ru/pay?order=42#confirm';
    const navigate = vi.fn();

    expect(navigateToPaymentTarget(target, 'payment', navigate)).toBe(true);
    expect(navigate).toHaveBeenCalledOnce();
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
