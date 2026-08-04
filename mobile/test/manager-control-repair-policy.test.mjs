import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { isExplicitlyRepairableCommonInvoiceReason } = loadTsModule(
  'src/app/shared/manager-control-repair-policy.ts'
);

test('allows common-invoice repair only when the backend explicitly instructs it', () => {
  assert.equal(isExplicitlyRepairableCommonInvoiceReason(
    'Создание платежной ссылки остановилось до отправки. Рекомендация: нажмите «Починить», чтобы повторить.'
  ), true);
  assert.equal(isExplicitlyRepairableCommonInvoiceReason(
    'Счет завис. Рекомендация: нажмите "Починить", чтобы продолжить.'
  ), true);
});

test('keeps payment and migration reconciliation cards out of automatic repair', () => {
  assert.equal(isExplicitlyRepairableCommonInvoiceReason(
    'Проблема T-Bank. Откройте счет и сверьте состояние платежа вручную.'
  ), false);
  assert.equal(isExplicitlyRepairableCommonInvoiceReason(
    'Ошибка общего счета: migration_common_payment_registry. Проверьте причину вручную.'
  ), false);
  assert.equal(isExplicitlyRepairableCommonInvoiceReason(null), false);
});
