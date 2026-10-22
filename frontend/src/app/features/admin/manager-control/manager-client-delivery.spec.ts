import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { ManagerApi } from '../../../core/manager.api';
import { ManagerControlApi, type ManagerControlConcreteItem, type ManagerControlManagerDetail } from '../../../core/manager-control.api';
import { ToastService } from '../../../shared/toast.service';
import { ManagerControlComponent } from './manager-control.component';

describe('queued manager messages in the web card', () => {
  const card: ManagerControlConcreteItem = { controlEntityId: 41, type: 'ORDER', title: 'Fixture', itemStatus: 'OPEN', contactText: 'Fixture reply' };
  const delivery = { operationId: '00000000-0000-0000-0000-000000000041', status: 'QUEUED', attempts: 0, errorCode: null };
  const detail = (example = card) => ({ managerId: 7, dailyControlId: 1, items: [{ itemId: 1, group: 'ACTION', itemType: 'CLIENT_CHAT_UNANSWERED', count: 1, examples: [example] }] }) as ManagerControlManagerDetail;

  it.each(['send', 'reply'] as const)('%s retains the accepted card and prevents a second POST until the receipt arrives', async action => {
    const accepted = { ...card, itemStatus: 'ACTION_TAKEN' as const, delivery };
    const send = vi.fn().mockReturnValue(of(accepted));
    const receipts = new Subject<typeof delivery>();
    const toast = { info: vi.fn(), success: vi.fn(), error: vi.fn() };
    const detailsRead = vi.fn().mockReturnValue(of({ ...detail(), items: [] }));
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: { sendClientMessage: send, replyToClientMessage: send, deliveryOperation: () => receipts,
        managerDetails: detailsRead, today: () => of({ managers: [] }) } },
      { provide: ManagerApi, useValue: {} }, { provide: ToastService, useValue: toast },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {} }, paramMap: new Subject() } },
      { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlComponent, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlComponent), page = fixture.componentInstance;
    page.detail.set(detail());
    page.unansweredReplyDrafts.set({ 41: 'Fixture reply' });
    const submit = () => action === 'send' ? page.sendClientMessage(card) : page.sendUnansweredReply(card);
    submit(); submit();
    expect(send).toHaveBeenCalledTimes(1);
    expect(page.detail()?.items[0].examples).toHaveLength(1);
    expect(page.deliveryMessage(card)).toContain('очереди');
    expect(page.deliveryBlocked(card)).toBe(true);
    expect(toast.success).not.toHaveBeenCalled();
    receipts.next({ ...delivery, status: 'SENT' });
    for (let n = 0; n < 8; n++) await Promise.resolve();
    expect(detailsRead).toHaveBeenCalledExactlyOnceWith(7);
    expect(send).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  function pageWithFreshCard(fresh: ManagerControlConcreteItem, admin = false) {
    const receipts = new Subject<typeof delivery>();
    const toast = { info: vi.fn(), success: vi.fn(), error: vi.fn() };
    const api = {
      markStage: vi.fn().mockReturnValue(of(detail(fresh))),
      repairConcreteItem: vi.fn().mockReturnValue(of({ ...card, delivery: null, comment: null })),
      sendClientMessage: vi.fn().mockReturnValue(of(fresh)),
      replyToClientMessage: vi.fn().mockReturnValue(of(fresh)),
      actionConcreteItem: vi.fn(),
      deliveryOperation: vi.fn().mockReturnValue(receipts),
      managerDetails: vi.fn(),
      today: () => of({ managers: [] })
    };
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: api },
      { provide: ManagerApi, useValue: {} },
      { provide: ToastService, useValue: toast },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => admin } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {} }, paramMap: new Subject() } },
      { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlComponent, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlComponent), page = fixture.componentInstance;
    page.detail.set(detail());
    return { fixture, page, api, receipts, toast };
  }

  it('clears a cached successful operation when a fresh detail allows a new follow-up', () => {
    const fresh = { ...card, delivery: null };
    const { fixture, page } = pageWithFreshCard(fresh);
    page.deliveries.set({ 41: { ...delivery, status: 'SENT' } });
    expect(page.deliveryBlocked(card)).toBe(true);

    page.markStage('MORNING_DONE');

    expect(page.deliveryBlocked(fresh)).toBe(false);
    expect(page.deliveryMessage(fresh)).toBeNull();
    expect(page.deliveries()[41]).toBeUndefined();
    fixture.destroy();
  });

  it('retains the scheduled uncertainty after reload without starting a receipt poll or a repeat send', async () => {
    const reason = 'Результат старой отправки неизвестен. Проверьте клиентский чат.';
    const fresh = { ...card, delivery: null, clientMessageSendBlockedReason: reason, chatUrl: 'https://chat.example.test' };
    const { fixture, page, api, toast } = pageWithFreshCard(fresh);
    page.markStage('MORNING_DONE');
    const current = page.detail()!.items[0].examples[0];
    page.preparedContactItemIds.set(new Set([41]));

    expect(page.deliveryBlocked(current)).toBe(true);
    expect(page.deliveryMessage(current)).toBe(reason);
    expect(page.contactFollowUpHint(current)).toBe(reason);
    expect(page.concreteActionTitle(current, 'ACTION_TAKEN', 'Отправлено')).toBe(reason);
    expect(page.isConcreteActionDisabled(current, 'ACTION_TAKEN')).toBe(true);
    expect(page.chatUrl(current)).toBe(fresh.chatUrl);
    page.sendClientMessage(current);
    page.markConcreteDone(current);
    await page.copyContactText(current);
    expect(api.sendClientMessage).not.toHaveBeenCalled();
    expect(api.actionConcreteItem).not.toHaveBeenCalled();
    expect(api.deliveryOperation).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('Сначала проверьте отправку', reason);
    fixture.destroy();
  });

  it('allows a different client reply instead of blocking all communications for an order reminder', () => {
    const fresh = { ...card, type: 'CLIENT_CHAT_UNANSWERED', delivery: null,
      clientMessageSendBlockedReason: 'Не повторяйте напоминание заказа' };
    const { fixture, page, api } = pageWithFreshCard(fresh);
    page.unansweredReplyDrafts.set({ 41: 'Ответ на новый вопрос клиента' });
    expect(page.deliveryBlocked(fresh)).toBe(false);
    page.sendUnansweredReply(fresh);
    expect(api.replyToClientMessage).toHaveBeenCalledOnce();
    fixture.destroy();
  });

  it('opens one handoff dialog and releases the card when the user cancels', () => {
    const fresh = { ...card, type: 'AUTOMATION_FAILURE', reason: 'state_transaction_outcome_uncertain' };
    const { fixture, page, api } = pageWithFreshCard(fresh, true);
    page.handoffUncertainDelivery(fresh);
    page.handoffUncertainDelivery({ ...fresh, controlEntityId: 42 });
    expect(page.deliveryHandoffItem()).toBe(fresh);
    expect(page.isConcreteUpdating(41)).toBe(true);
    expect(page.isConcreteUpdating(42)).toBe(false);
    page.closeDeliveryHandoff();
    expect(page.deliveryHandoffItem()).toBeNull();
    expect(page.isConcreteUpdating(41)).toBe(false);
    expect(api.sendClientMessage).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it.each(['QUEUED', 'UNKNOWN'])('keeps the authoritative %s operation blocked after refreshing details', status => {
    const current = { ...delivery, operationId: '00000000-0000-0000-0000-000000000042', status };
    const fresh = { ...card, delivery: current };
    const { fixture, page, api } = pageWithFreshCard(fresh);
    page.deliveries.set({ 41: { ...delivery, status: 'SENT' } });

    page.markStage('MORNING_DONE');

    expect(page.deliveryBlocked(fresh)).toBe(true);
    expect(page.deliveries()[41]).toEqual(current);
    expect(api.deliveryOperation).toHaveBeenCalledExactlyOnceWith(41, current.operationId);
    fixture.destroy();
  });

  it('ignores a late receipt after a fresh card response clears the previous operation', async () => {
    const pending = { ...card, delivery };
    const { fixture, page, api, receipts } = pageWithFreshCard(pending);
    page.markStage('MORNING_DONE');
    expect(page.deliveryBlocked(pending)).toBe(true);

    page.repairConcreteItem(pending);
    receipts.next({ ...delivery, status: 'SENT' });
    for (let n = 0; n < 8; n++) await Promise.resolve();

    const fresh = page.detail()!.items[0].examples[0];
    expect(page.deliveryBlocked(fresh)).toBe(false);
    expect(page.deliveries()[41]).toBeUndefined();
    expect(api.managerDetails).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it.each(['send', 'reply'] as const)('%s describes follow-up after confirmed delivery without claiming resolution', action => {
    const fresh = {
      ...card, itemStatus: 'ACTION_TAKEN' as const, followUpAt: '2026-10-25T12:00:00',
      delivery: { ...delivery, status: 'SENT' }
    };
    const { fixture, page, toast } = pageWithFreshCard(fresh);
    page.unansweredReplyDrafts.set({ 41: 'Fixture reply' });

    if (action === 'send') page.sendClientMessage(card);
    else page.sendUnansweredReply(card);

    expect(toast.success).toHaveBeenCalledExactlyOnceWith(
      action === 'send' ? 'Сообщение отправлено' : 'Ответ отправлен',
      expect.stringContaining('Повторная проверка:')
    );
    expect(toast.info).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it.each(['send', 'reply'] as const)('%s keeps an unresolved card visible for a response without delivery metadata', action => {
    const fresh = { ...card, itemStatus: 'ACTION_TAKEN' as const, delivery: null };
    const { fixture, page, toast } = pageWithFreshCard(fresh);
    page.unansweredReplyDrafts.set({ 41: 'Fixture reply' });

    if (action === 'send') page.sendClientMessage(card);
    else page.sendUnansweredReply(card);

    expect(toast.success).toHaveBeenCalledExactlyOnceWith(
      action === 'send' ? 'Сообщение отправлено' : 'Ответ отправлен', 'Карточка остаётся на контроле'
    );
    expect(page.detail()?.items[0].examples).toHaveLength(1);
    fixture.destroy();
  });
});
