import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  configuredPaymentTarget,
  navigateToPaymentTarget,
  paymentTargetForUpdate,
  safePaymentNavigationTarget
} = loadTsModule(
  'src/app/shared/payment-navigation.ts'
);

for (const value of [
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
]) {
  test(`rejects unsafe payment target without navigation: ${value}`, () => {
    const navigated = [];

    assert.equal(navigateToPaymentTarget(value, 'sbp', (target) => navigated.push(target)), false);
    assert.deepEqual(navigated, []);
  });
}

for (const value of [
  'https://qr.nspk.ru/AS100000000111',
  'https://www.tbank.ru/mybank/payments/qr-pay/AD100018PU4SB0748MTQ2RG6K2S26V24',
  'https://payzonaecom.com/mobile-public/goto/qr/AD10001VKP9AV8AC9CGR2FHJ7LNIUJ9C',
  'bank100000000111://qr.nspk.ru/AS100000000111',
  'bankb2b100000000111://qr.nspk.ru/AR100000000111',
  'bankapp://pay/payment-sbp-bank'
]) {
  test(`allows supported SBP target: ${value}`, () => {
    assert.equal(safePaymentNavigationTarget(value, 'sbp'), value);
  });
}

test('keeps a valid public payment URL byte-for-byte unchanged', () => {
  const value = 'https://securepay.tinkoff.ru/pay?order=42#confirm';
  const navigated = [];

  assert.equal(navigateToPaymentTarget(value, 'payment', (target) => navigated.push(target)), true);
  assert.deepEqual(navigated, [value]);
});

test('allows current T-Bank short payment URLs from production', () => {
  const value = 'https://pay.tbank.ru/CwEMz5Cw';
  const navigated = [];

  assert.equal(navigateToPaymentTarget(value, 'payment', (target) => navigated.push(target)), true);
  assert.deepEqual(navigated, [value]);
});

for (const value of [
  'http://securepay.tinkoff.ru/pay',
  'https://securepay.tinkoff.ru.evil.test/pay',
  'https://evil.test/pay'
]) {
  test(`rejects untrusted generated payment URL: ${value}`, () => {
    assert.equal(safePaymentNavigationTarget(value, 'payment'), null);
  });
}

test('keeps arbitrary HTTPS manual links for configured acquiring banks', () => {
  assert.equal(
    safePaymentNavigationTarget('https://pay.alfabank.ru/sc/example', 'manual'),
    'https://pay.alfabank.ru/sc/example'
  );
});

test('does not substitute another recipient for a quarantined backend destination', () => {
  assert.equal(configuredPaymentTarget(''), '');
  assert.equal(configuredPaymentTarget(null), '');
  assert.equal(configuredPaymentTarget('  https://recipient.example/pay  '), 'https://recipient.example/pay');
  assert.equal(paymentTargetForUpdate('', false), null);
  assert.equal(paymentTargetForUpdate('https://new.example/pay', false), 'https://new.example/pay');
});
