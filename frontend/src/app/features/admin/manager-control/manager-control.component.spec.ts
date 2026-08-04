import { describe, expect, it } from 'vitest';
import { isExplicitlyRepairableCommonInvoiceReason } from './manager-control.component';

describe('isExplicitlyRepairableCommonInvoiceReason', () => {
  it('keeps repair available for a TLS failure declared safe by the backend', () => {
    expect(isExplicitlyRepairableCommonInvoiceReason(
      'Создание платежной ссылки остановилось на проверке сертификата до отправки запроса в T-Bank. '
      + 'Рекомендация: нажмите «Починить», чтобы безопасно удалить незавершенную попытку.'
    )).toBe(true);
  });

  it.each([
    'Проблема при создании платежной ссылки T-Bank. Рекомендация: откройте «Счет» и сверьте состояние платежа в банке.',
    'Создание платежной ссылки остановилось на проверке сертификата, но у заказа есть отдельный незавершенный платеж. Сначала сверьте этот платеж вручную.',
    'Ошибка общего счета: migration_common_payment_registry. Откройте «Счет» и проверьте причину вручную.',
    'Допубликационные позиции блокируют сбор.'
  ])('hides repair for a manual reconciliation reason: %s', (reason) => {
    expect(isExplicitlyRepairableCommonInvoiceReason(reason)).toBe(false);
  });
});
