import { describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { ManagerApi } from '../../../core/manager.api';
import { ManagerControlApi } from '../../../core/manager-control.api';
import { ToastService } from '../../../shared/toast.service';
import {
  automationWaitingSlotDate,
  isAutomationWaitingForSlotReason,
  isTelegramBindingAutomationIssue,
  isExplicitlyRepairableCommonInvoiceReason,
  ManagerControlComponent,
  needsManagerControlDetailSync,
  shouldHideClientChatUnansweredAfterAction,
  telegramGroupLinkCommand
} from './manager-control.component';

describe('persistent repair result', () => {
  it('keeps the actual 409 reason after a refreshed card and clears it for a new successful check', () => {
    const card = { controlEntityId: 8788, type: 'ORDER', title: 'Fixture', itemStatus: 'OPEN',
      reason: 'Исход предыдущей отправки ещё не подтверждён', delivery: null } as any;
    const detail = (example = card) => ({ managerId: 2, dailyControlId: 1,
      items: [{ itemId: 1, examples: [example] }] }) as any;
    const first = new Subject<any>(), second = new Subject<any>();
    const repair = vi.fn().mockReturnValueOnce(first).mockReturnValueOnce(second);
    const send = vi.fn();
    const toast = { warning: vi.fn(), error: vi.fn(), success: vi.fn() };
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: { repairConcreteItem: repair, sendClientMessage: send,
        today: () => of({ managers: [] }) } },
      { provide: ManagerApi, useValue: {} }, { provide: ToastService, useValue: toast },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {} }, paramMap: new Subject() } },
      { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlComponent, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlComponent), page = fixture.componentInstance;
    page.detail.set(detail());
    page.repairConcreteItem(card);
    const reason = 'Существующая отправка проверена, но квитанция не найдена. Новое сообщение не отправлялось.';
    first.error({ status: 409, error: { message: reason } });
    expect(page.concreteRepairResult(card)).toEqual({ message: reason, deliveryCheck: true });
    expect(toast.warning).toHaveBeenCalledWith('Проверка отправки', reason);
    expect(toast.success).not.toHaveBeenCalled();

    const refreshed = { ...card, updatedAt: '2026-10-23T21:00:00' };
    (page as any).applyDetail(detail(refreshed));
    expect(page.concreteRepairResult(refreshed)?.message).toBe(reason);
    expect(page.concreteRepairResult({ ...refreshed, controlEntityId: 8789 })).toBeNull();
    expect(page.concreteRepairResult({ ...refreshed, itemStatus: 'RESOLVED' })).toBeNull();
    expect(repair).toHaveBeenCalledTimes(1);
    expect(send).not.toHaveBeenCalled();

    page.repairConcreteItem(refreshed);
    expect(page.concreteRepairResult(refreshed)).toBeNull();
    second.next({ ...refreshed, itemStatus: 'ACTION_TAKEN', comment: 'Квитанция подтверждена. Ожидаем ответ клиента.' });
    expect(page.concreteRepairResult(refreshed)).toBeNull();
    expect(toast.success).toHaveBeenCalledWith('Результат отправки проверен', 'Квитанция подтверждена. Ожидаем ответ клиента.');
    expect(send).not.toHaveBeenCalled();
    fixture.destroy();
  });
});

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
  it('keeps the binding action for the manager-friendly description', () => {
    expect(isTelegramBindingAutomationIssue({
      type: 'AUTOMATION_FAILURE', reason: 'Группа Telegram не привязана к компании'
    })).toBe(true);
  });

  it('allows a receipt-only recheck while a delivery is being checked manually', () => {
    const canRepair = ManagerControlComponent.prototype.canRepairAutomationIssue;
    expect(canRepair.call({ isAutomationWaitingForSlot: () => false } as any, {
      type: 'AUTOMATION_FAILURE', reason: 'Группа Telegram не привязана к компании · Ручная проверка продолжается. Эта карточка остаётся открытой.'
    } as any)).toBe(true);
  });

  it('offers receipt reconciliation for a manual UNKNOWN order operation even without a scheduled error', () => {
    const card = { type: 'ORDER', reason: 'Заказ долго ожидает проверки', delivery: { status: 'UNKNOWN' } } as any;
    expect(ManagerControlComponent.prototype.canRepairAutomationIssue.call({} as any, card)).toBe(true);
    expect(ManagerControlComponent.prototype.isDeliveryReconciliation.call({} as any, card)).toBe(true);
  });

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
