import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
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
    expect(toast.warning).toHaveBeenCalledWith('Результат проверки', reason);
    expect(toast.success).not.toHaveBeenCalled();

    const refreshed = { ...card, updatedAt: '2026-10-23T21:00:00' };
    (page as any).applyDetail(detail(refreshed));
    expect(page.concreteRepairResult(refreshed)?.message).toBe(reason);
    expect(page.concreteRepairResult({ ...refreshed, controlEntityId: 8789 })).toBeNull();
    expect(page.concreteRepairResult({ ...refreshed, itemStatus: 'RESOLVED' })).toBeNull();
    expect(page.concreteRepairResult({ ...refreshed, delivery: { operationId: 'op', status: 'SENT', attempts: 1, errorCode: null } })).toBeNull();
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

describe('automatic delivery check outcomes', () => {
  function setup() {
    const card = { controlEntityId: 8788, type: 'ORDER', title: 'Fixture', itemStatus: 'OPEN',
      reason: 'Исход предыдущей отправки ещё не подтверждён', delivery: null } as any;
    const response = new Subject<any>();
    const api = { repairConcreteItem: vi.fn().mockReturnValue(response), sendClientMessage: vi.fn(),
      deliveryOperation: vi.fn().mockReturnValue(new Subject()), today: () => of({ managers: [] }) };
    const toast = { warning: vi.fn(), error: vi.fn(), success: vi.fn() };
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: api }, { provide: ManagerApi, useValue: {} },
      { provide: ToastService, useValue: toast }, { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {} }, paramMap: new Subject() } },
      { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlComponent, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlComponent), page = fixture.componentInstance;
    page.detail.set({ managerId: 2, dailyControlId: 1, items: [{ itemId: 1, examples: [card] }] } as any);
    return { card, response, api, toast, fixture, page };
  }

  it.each([409, 503])('keeps the server result for %s without a second POST or a success claim', status => {
    const { card, response, api, toast, fixture, page } = setup();
    page.repairConcreteItem(card);
    page.repairConcreteItem(card);
    expect(page.repairActionLabel(card)).toBe('Проверяем…');
    const message = status === 409
      ? 'Подтверждение пока не найдено. Проверка продолжается автоматически.'
      : 'WhatsApp временно недоступен. Система продолжит проверку.';
    response.error({ status, error: { message } });
    expect(page.concreteRepairResult(card)).toEqual({ message, deliveryCheck: true });
    expect(page.isConcreteUpdating(card.controlEntityId)).toBe(false);
    expect(toast.success).not.toHaveBeenCalled();
    expect(api.repairConcreteItem).toHaveBeenCalledTimes(1);
    expect(api.sendClientMessage).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('does not invent a queued check for a source conflict', () => {
    const { card, response, fixture, page } = setup();
    page.repairConcreteItem(card);
    const message = 'Источник отправки изменился. Результат не применён к этой карточке.';
    response.error({ status: 409, error: { message } });
    expect(page.concreteRepairResult(card)?.message).toBe(message);
    expect(page.concreteRepairResult(card)?.message).not.toMatch(/запущен|продолжается|в фоне/i);
    fixture.destroy();
  });

  it.each([null, { operationId: 'unknown-operation', status: 'UNKNOWN', attempts: 1, errorCode: null }])(
    'keeps a 200 response with an open unconfirmed card visible', delivery => {
      const { card, response, api, toast, fixture, page } = setup();
      page.repairConcreteItem(card);
      response.next({ ...card, delivery, comment: 'Подтверждение пока не найдено. Проверка продолжается автоматически.' });
      expect(toast.success).not.toHaveBeenCalled();
      expect(toast.warning).toHaveBeenCalledWith('Проверка ещё не завершена', expect.stringContaining('Подтверждение пока не найдено'));
      expect(page.detail()?.items[0].examples).toHaveLength(1);
      expect(page.concreteRepairResult(card)?.message).toContain('Проверка продолжается автоматически');
      expect(page.isConcreteUpdating(card.controlEntityId)).toBe(false);
      expect(api.sendClientMessage).not.toHaveBeenCalled();
      fixture.destroy();
    }
  );

  it('shows confirmed delivery without claiming that the underlying issue was resolved', () => {
    const { card, response, toast, fixture, page } = setup();
    page.repairConcreteItem(card);
    response.next({ ...card, delivery: { operationId: 'confirmed-operation', status: 'SENT', attempts: 1, errorCode: null },
      comment: 'Сообщение найдено. Заказ остаётся на контроле до ответа клиента.' });
    expect(toast.success).toHaveBeenCalledWith('Отправка подтверждена', 'Сообщение найдено. Заказ остаётся на контроле до ответа клиента.');
    expect(page.detail()?.items[0].examples).toHaveLength(1);
    expect(page.concreteRepairResult(card)).toBeNull();
    fixture.destroy();
  });

  it('does not display a legacy operation marker as a manager instruction', () => {
    const { card, response, toast, fixture, page } = setup();
    page.repairConcreteItem(card);
    response.next({ ...card, comment: 'client_message_delivery_unknown:00000000-0000-0000-0000-000000000041' });
    expect(page.concreteRepairResult(card)?.message).not.toMatch(/client_message|00000000/);
    expect(page.concreteRepairResult(card)?.message).toContain('заблокирована');
    expect(toast.success).not.toHaveBeenCalled();
    fixture.destroy();
  });
});

describe('coalesced background repair result reads', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.spyOn(document, 'hidden', 'get').mockReturnValue(false); });
  afterEach(() => { TestBed.resetTestingModule(); vi.restoreAllMocks(); vi.useRealTimers(); });

  function setup() {
    const cards = [
      { controlEntityId: 41, entityId: 115, type: 'COMMON_INVOICE', title: 'Invoice', itemStatus: 'OPEN', reason: 'message_send_in_progress' },
      { controlEntityId: 42, entityId: 75, type: 'AUTOMATION_FAILURE', title: 'Scheduled', itemStatus: 'OPEN', reason: 'state_transaction_outcome_uncertain' }
    ] as any[];
    const detail = (examples = cards) => ({ managerId: 2, dailyControlId: 19, controlDate: '2026-10-24',
      items: [{ itemId: 1, count: examples.length, hiddenExampleCount: 0, examples }] }) as any;
    const api = { managerDetails: vi.fn().mockImplementation(() => of(detail())),
      repairConcreteItem: vi.fn().mockImplementation(() => throwError(() => ({ status: 409,
        error: { message: 'Подтверждение пока не найдено. Система продолжит сверку автоматически.' } }))),
      sendClientMessage: vi.fn(), syncManagerDetails: vi.fn(), actionConcreteItem: vi.fn(),
      deliveryOperation: vi.fn().mockReturnValue(new Subject()), today: () => of({ managers: [] }) };
    const toast = { warning: vi.fn(), error: vi.fn(), success: vi.fn() };
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: api }, { provide: ManagerApi, useValue: {} },
      { provide: ToastService, useValue: toast }, { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {} }, paramMap: new Subject() } },
      { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlComponent, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlComponent), page = fixture.componentInstance;
    page.detail.set(detail());
    return { cards, detail, api, toast, fixture, page };
  }

  it('updates a card when background recovery resolves it five minutes later, without another mutation', async () => {
    const { page, cards, detail, api, toast } = setup();
    const started = Date.now();
    api.managerDetails.mockImplementation(() => of(detail(Date.now() - started < 300_000
      ? cards : [{ ...cards[0], itemStatus: 'RESOLVED' }, cards[1]])));
    page.repairConcreteItem(cards[0]);
    await vi.advanceTimersByTimeAsync(270_000);
    expect(page.detail()!.items[0].examples).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(page.detail()!.items[0].examples.map(card => card.controlEntityId)).toEqual([42]);
    expect(page.concreteRepairResult(cards[0])).toBeNull();
    expect(api.managerDetails).toHaveBeenCalledTimes(10);
    await vi.advanceTimersByTimeAsync(90_000);
    expect(api.managerDetails).toHaveBeenCalledTimes(10);
    expect(api.repairConcreteItem).toHaveBeenCalledTimes(1);
    expect(api.sendClientMessage).not.toHaveBeenCalled();
    expect(api.syncManagerDetails).not.toHaveBeenCalled();
    expect(api.actionConcreteItem).not.toHaveBeenCalled();
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('coalesces two cards into one non-overlapping GET and preserves every form draft', async () => {
    const { page, cards, detail, api } = setup();
    const pending = new Subject<any>();
    api.managerDetails.mockReturnValue(pending);
    page.detailComments.set({ 1: 'Unsaved section comment' });
    page.detailConcreteComments.set({ 41: 'Unsaved invoice comment', 42: 'Unsaved scheduled comment' });
    page.unansweredReplyDrafts.set({ 99: 'Unsaved client reply' });
    page.preparedContactItemIds.set(new Set([99]));
    page.repairConcreteItem(cards[0]); page.repairConcreteItem(cards[1]);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.managerDetails).toHaveBeenCalledExactlyOnceWith(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.managerDetails).toHaveBeenCalledTimes(1);
    pending.next(detail([{ ...cards[0], comment: 'Changed server comment' },
      { ...cards[1], itemStatus: 'ACTION_TAKEN', reason: 'Ожидаем ответ клиента', comment: 'Отправка подтверждена' }]));
    expect(page.detail()!.items[0].examples[1].itemStatus).toBe('ACTION_TAKEN');
    expect(page.detailComments()).toEqual({ 1: 'Unsaved section comment' });
    expect(page.detailConcreteComments()).toEqual({ 41: 'Unsaved invoice comment', 42: 'Unsaved scheduled comment' });
    expect(page.unansweredReplyDrafts()).toEqual({ 99: 'Unsaved client reply' });
    expect([...page.preparedContactItemIds()]).toEqual([99]);
    expect(api.repairConcreteItem).toHaveBeenCalledTimes(2);
    expect(page.concreteRepairResult(cards[1])).toBeNull();
  });

  it('ignores a late response after navigation closes the details', async () => {
    const { page, cards, api, detail } = setup();
    const pending = new Subject<any>();
    api.managerDetails.mockReturnValue(pending);
    page.repairConcreteItem(cards[0]);
    await vi.advanceTimersByTimeAsync(30_000);
    page.personalControl = true;
    page.closeDetails();
    page.detail.set({ ...detail(), managerId: 3, dailyControlId: 20 });
    pending.next(detail([{ ...cards[0], itemStatus: 'RESOLVED' }]));
    await vi.advanceTimersByTimeAsync(90_000);
    expect(page.detail()!.managerId).toBe(3);
    expect(page.detail()!.items[0].examples).toHaveLength(2);
    expect(api.managerDetails).toHaveBeenCalledTimes(1);
  });

  it('does not overwrite a more recent card action with an in-flight read', async () => {
    const { page, cards, api, detail } = setup();
    const pending = new Subject<any>();
    api.managerDetails.mockReturnValue(pending);
    page.repairConcreteItem(cards[0]);
    await vi.advanceTimersByTimeAsync(30_000);
    const newer = { ...cards[0], itemStatus: 'DEFERRED', comment: 'New acknowledged action' };
    (page as any).patchDetailConcreteItem(cards[0], newer);
    pending.next(detail([{ ...cards[0], itemStatus: 'RESOLVED' }, cards[1]]));
    expect(page.detail()!.items[0].examples[0]).toMatchObject(newer);
  });

  it('keeps UNKNOWN visible even if the server marks follow-up, and stops reads after ten minutes', async () => {
    const { page, cards, api, detail, toast } = setup();
    const unknown = { ...cards[0], itemStatus: 'ACTION_TAKEN', followUpAt: '2026-10-25T12:00:00',
      delivery: { operationId: 'existing-op', status: 'UNKNOWN', attempts: 1, errorCode: null } };
    api.managerDetails.mockImplementation(() => of(detail([unknown, cards[1]])));
    page.repairConcreteItem(cards[0]);
    await vi.advanceTimersByTimeAsync(600_000);
    const reads = api.managerDetails.mock.calls.length;
    expect(reads).toBeGreaterThan(0); expect(reads).toBeLessThanOrEqual(20);
    await vi.advanceTimersByTimeAsync(90_000);
    expect(api.managerDetails).toHaveBeenCalledTimes(reads);
    expect(page.detail()!.items[0].examples).toHaveLength(2);
    expect(page.deliveryBlocked(unknown)).toBe(true);
    expect(page.concreteRepairResult(unknown)?.message).toContain('продолжит сверку');
    expect(toast.success).not.toHaveBeenCalled();
    expect(api.deliveryOperation).not.toHaveBeenCalled();
    expect(api.repairConcreteItem).toHaveBeenCalledTimes(1);
  });

  it('observes an open UNKNOWN 200 repair result and retries failed reads without another repair', async () => {
    const { page, cards, api, detail } = setup();
    api.repairConcreteItem.mockReturnValue(of({ ...cards[0], comment: 'Ожидаем квитанцию' }));
    api.managerDetails.mockReturnValueOnce(throwError(() => ({ status: 503 })))
      .mockReturnValueOnce(of(detail([cards[1]])));
    page.repairConcreteItem(cards[0]);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(page.detail()!.items[0].examples).toHaveLength(2);
    expect(page.concreteRepairResult(cards[0])?.message).toBe('Ожидаем квитанцию');
    await vi.advanceTimersByTimeAsync(30_000);
    expect(page.detail()!.items[0].examples.map(card => card.controlEntityId)).toEqual([42]);
    expect(api.repairConcreteItem).toHaveBeenCalledTimes(1);
  });

  it('pauses reads for a hidden tab and rejects a response for a different control date', async () => {
    const { page, cards, api, detail } = setup();
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    page.repairConcreteItem(cards[0]);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(api.managerDetails).not.toHaveBeenCalled();
    hidden.mockReturnValue(false);
    api.managerDetails.mockReturnValue(of({ ...detail([]), controlDate: '2026-10-25', dailyControlId: 99 }));
    await vi.advanceTimersByTimeAsync(90_000);
    expect(api.managerDetails).toHaveBeenCalledTimes(1);
    expect(page.detail()!.controlDate).toBe('2026-10-24');
    expect(page.detail()!.items[0].examples).toHaveLength(2);
  });
});

describe('isExplicitlyRepairableCommonInvoiceReason', () => {
  it.each([
    'Результат отправки сообщения по счету не подтвержден. Нажмите «Проверить отправку»: система сверит существующую отправку.',
    'message_send_in_progress', 'message_send_stale: previous attempt', 'message_send_unknown'
  ])('offers receipt-only reconciliation for invoice 115 without manual send: %s', reason => {
    const card = { type: 'COMMON_INVOICE', reason } as any;
    expect(ManagerControlComponent.prototype.canRepairAutomationIssue.call({} as any, card)).toBe(true);
    expect(ManagerControlComponent.prototype.isDeliveryReconciliation.call({} as any, card)).toBe(true);
  });

  it('does not offer an invoice reminder check for an unrelated payment notification failure', () => {
    const card = { type: 'COMMON_INVOICE', reason: 'Результат уведомления об оплате не подтвержден.' } as any;
    expect(ManagerControlComponent.prototype.canRepairAutomationIssue.call({ isAutomationWaitingForSlot: () => false } as any, card)).toBe(false);
    expect(ManagerControlComponent.prototype.isDeliveryReconciliation.call({} as any, card)).toBe(false);
  });

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
