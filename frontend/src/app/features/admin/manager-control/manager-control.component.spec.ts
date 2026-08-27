import { describe, expect, it } from 'vitest';
import {
  automationWaitingSlotDate,
  isAutomationWaitingForSlotReason,
  isTelegramBindingAutomationIssue,
  isExplicitlyRepairableCommonInvoiceReason,
  needsManagerControlDetailSync,
  shouldHideClientChatUnansweredAfterAction,
  telegramGroupLinkCommand
} from './manager-control.component';

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

describe('shouldHideClientChatUnansweredAfterAction', () => {
  it.each(['OPEN', 'DEFERRED', 'ACTION_TAKEN', 'ACKNOWLEDGED'] as const)(
    'keeps an unanswered card visible while its control status is %s',
    (itemStatus) => {
      expect(shouldHideClientChatUnansweredAfterAction({ itemStatus })).toBe(false);
    }
  );

  it('hides an unanswered card only after the backend confirms resolution', () => {
    expect(shouldHideClientChatUnansweredAfterAction({ itemStatus: 'RESOLVED' })).toBe(true);
  });
});

describe('Telegram automation binding', () => {
  it('recognizes a missing Telegram chatId as a binding action', () => {
    expect(isTelegramBindingAutomationIssue({
      type: 'AUTOMATION_FAILURE',
      reason: 'ARCHIVE_REORDER_OFFER · telegram_group_missing · Для Telegram-группы не задан chatId'
    })).toBe(true);
  });

  it('does not relabel unrelated automation failures', () => {
    expect(isTelegramBindingAutomationIssue({
      type: 'AUTOMATION_FAILURE',
      reason: 'rate_limited · Следующий слот отправки'
    })).toBe(false);
  });

  it('builds a group command for a bot that is already present', () => {
    expect(telegramGroupLinkCommand(
      'https://t.me/O_Company_Bot?startgroup=cSignedToken_123'
    )).toBe('/start@O_Company_Bot cSignedToken_123');
  });

  it.each(['', 'https://t.me/O_Company_Bot', 'https://example.com/O_Company_Bot?startgroup=token'])(
    'rejects an unusable invite URL: %s',
    (url) => expect(telegramGroupLinkCommand(url)).toBe('')
  );
});
describe('scheduled automation queue', () => {
  const beforeSlot = new Date(2026, 7, 13, 18, 0).getTime();
  const afterSlot = new Date(2026, 7, 13, 20, 0).getTime();

  it.each([
    'Очередь автоответчика исправна. Следующий слот отправки: 2026-08-13T19:00.',
    'Сообщение уже запланировано. Система отправит его автоматически: 2026-08-13T19:00.'
  ])('recognizes a future scheduled attempt: %s', (reason) => {
    expect(isAutomationWaitingForSlotReason(reason, beforeSlot)).toBe(true);
    expect(automationWaitingSlotDate(reason)?.getHours()).toBe(19);
  });

  it('stops treating a scheduled reason as waiting after its slot', () => {
    expect(isAutomationWaitingForSlotReason(
      'Сообщение уже запланировано. Система отправит его автоматически: 2026-08-13T19:00.',
      afterSlot
    )).toBe(false);
  });

  it('does not hide a real automation failure', () => {
    expect(isAutomationWaitingForSlotReason(
      'Автоответчик не обработал заказ: telegram_group_missing',
      beforeSlot
    )).toBe(false);
  });
});

describe('needsManagerControlDetailSync', () => {
  it('requires sync when an open action card has no concrete id', () => {
    expect(needsManagerControlDetailSync({
      dailyControlId: 7,
      items: [{
        itemStatus: 'OPEN',
        group: 'ACTION',
        count: 1,
        examples: [{ title: 'Заказ #1' }]
      }]
    } as any)).toBe(true);
  });

  it('does not require sync for a prepared open action card', () => {
    expect(needsManagerControlDetailSync({
      dailyControlId: 7,
      items: [{
        itemStatus: 'OPEN',
        group: 'ACTION',
        count: 1,
        examples: [{ controlEntityId: 11, title: 'Заказ #1' }]
      }]
    } as any)).toBe(false);
  });

  it('requires sync when the day control itself is not created yet', () => {
    expect(needsManagerControlDetailSync({
      dailyControlId: null,
      items: []
    } as any)).toBe(true);
  });
});
