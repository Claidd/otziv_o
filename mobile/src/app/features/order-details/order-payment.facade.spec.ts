import { PageWriteTracker } from '../../core/page-write-tracker';
import { signal } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';
import { OrderPaymentFacade } from './order-payment.facade';
import { RouteEpochGuard } from '../../core/route-epoch.guard';
import type { PaymentRouteChangeContext, PaymentRouteRecipientsResponse, TbankPaymentStatus } from '../../core/api.service';
import type { MobilePaymentRecipientPickerRequest, MobilePaymentRecipientPickerResult } from '../../shared/payment-recipient-picker';

const context = () => ({ canChange: true, configuredMode: 'EMPLOYEE_REQUISITES', paymentLinkId: 71, expectedTargetPaymentProfileId: 81 }) as PaymentRouteChangeContext;
const bankLink = (patch: Partial<PaymentRouteChangeContext> = {}) => ({ ...context(), configuredMode: 'OWNER_TBANK', currentRecipient: ' Лидия З. ', ...patch }) as PaymentRouteChangeContext;
const people = () => ({ orderId: 1, paymentLinkId: 71, amountKopecks: 150_000, recipients: [
  { profileId: 44, name: 'Анна П.', role: 'SPECIALIST', orderSpecialist: false, manager: false, teamMember: true, workedWithClient: false, availableKopecks: 40_000, overrunKopecks: 110_000, current: false, blockReason: null }
] }) as PaymentRouteRecipientsResponse;
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

describe('order payment facade', () => {
  const create = () => {
    const route = new RouteEpochGuard(); route.change('order:1'); const env = { id: 1, canManage: true };
    const api = { getManagerOrderPaymentRouteChangeContext: vi.fn(() => of(context())), getManagerOrderPaymentRouteRecipients: vi.fn(() => of(people())), changeManagerOrderPaymentRoute: vi.fn(), markManagerOrderPaperInvoiceIssued: vi.fn(), createManagerOrderPaymentLink: vi.fn(), getTbankStatus: vi.fn() };
    const confirm = vi.fn().mockResolvedValue(true); const copy = vi.fn().mockResolvedValue(true); const reload = vi.fn(); const toast = vi.fn().mockResolvedValue({ present: vi.fn() });
    const pickRecipient = vi.fn<(request: MobilePaymentRecipientPickerRequest) => Promise<MobilePaymentRecipientPickerResult | null>>().mockResolvedValue(null);
    const error = signal<string | null>(null);
    const facade = new OrderPaymentFacade({ writes: new PageWriteTracker(), api, pickRecipient, orderId: () => env.id, hasDetails: () => true, canManage: () => env.canManage, capture: () => route.capture(), accepts: ticket => route.accepts(ticket), confirm, copy, reload, toast, error, mutationKey: signal<string | null>(null), errorMessage: (_, fallback) => fallback });
    return { facade, api, confirm, copy, reload, toast, env, route, pickRecipient, error };
  };
  /** A window that stays open: the test presses its buttons through the captured request. */
  const openWindow = (pickRecipient: ReturnType<typeof create>['pickRecipient']) => {
    let close!: (result: MobilePaymentRecipientPickerResult | null) => void; let request!: MobilePaymentRecipientPickerRequest;
    pickRecipient.mockImplementation(value => { request = value; return new Promise(resolve => close = resolve); });
    return { request: () => request, close: (result: MobilePaymentRecipientPickerResult | null) => close(result) };
  };
  it('cancels context GET on dismiss and accepts only the newly opened context', async () => {
    const { facade, api } = create(); const old = new Subject<PaymentRouteChangeContext>();
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(old);
    const read = facade.openPaymentRouteChange(); expect(old.observed).toBe(true); facade.closePaymentRouteChange();
    await read; expect(old.observed).toBe(false); expect(facade.paymentRouteContext()).toBeNull();
    await facade.openPaymentRouteChange(); old.next({ ...context(), paymentLinkId: 99 });
    expect(facade.paymentRouteContext()?.paymentLinkId).toBe(71);
  });
  it('reserves a command before confirmation and rejects confirmation after leave', async () => {
    const { facade, api, confirm } = create(); let approve!: (value: boolean) => void;
    confirm.mockReturnValue(new Promise<boolean>(resolve => approve = resolve)); await facade.openPaymentRouteChange();
    const first = facade.changePaymentRoute('OWNER_TBANK'); const second = facade.changePaymentRoute('OWNER_TBANK');
    expect(confirm).toHaveBeenCalledTimes(1); facade.deactivate(); approve(true); await Promise.all([first, second]);
    expect(api.changeManagerOrderPaymentRoute).not.toHaveBeenCalled();
  });
  it('captures financial identifiers, never cancels a dispatched mutation and ignores its late result', async () => {
    const { facade, api, route, env, reload, toast } = create(); const mutation = new Subject<{ clientNotificationScheduled: boolean }>();
    api.changeManagerOrderPaymentRoute.mockReturnValue(mutation); await facade.openPaymentRouteChange();
    const operation = facade.changePaymentRoute('OWNER_TBANK'); await settle();
    expect(api.changeManagerOrderPaymentRoute).toHaveBeenCalledWith(1, { expectedPaymentLinkId: 71, target: 'OWNER_TBANK', confirmedUnpaid: true, expectedTargetPaymentProfileId: 81 });
    facade.deactivate(); env.id = 2; route.change('order:2'); await facade.openPaymentRouteChange();
    expect(mutation.observed).toBe(true); mutation.next({ clientNotificationScheduled: true }); mutation.complete(); await operation;
    expect(facade.paymentRouteVisible()).toBe(true); expect(reload).not.toHaveBeenCalled(); expect(toast).not.toHaveBeenCalled();
  });
  it('requires manager permissions and both provider-neutral link capabilities', () => {
    const { facade, api, env } = create(); facade.tbankStatus.set({ managerUiEnabled: true, paymentLinksEnabled: true, enabled: false } as TbankPaymentStatus);
    expect(facade.canShowPaymentLinkAction()).toBe(true); env.canManage = false; facade.createPaymentLink();
    expect(api.createManagerOrderPaymentLink).not.toHaveBeenCalled(); expect(facade.canManagePaymentRoute()).toBe(false);
    env.canManage = true; facade.tbankStatus.set({ managerUiEnabled: true, paymentLinksEnabled: false } as TbankPaymentStatus);
    expect(facade.canShowPaymentLinkAction()).toBe(false);
  });
  it('issues a card number for an issued invoice from the window, without a separate confirmation', async () => {
    const { facade, api, confirm, pickRecipient, reload, toast } = create(); const picker = openWindow(pickRecipient);
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of(bankLink()));
    api.changeManagerOrderPaymentRoute.mockReturnValue(of({ clientNotificationScheduled: true }));
    await facade.openPaymentRouteChange(); const opened = facade.paymentRouteContext()!;
    expect(facade.cardNumberHint(opened)).toContain('Откроется окно');
    const operation = facade.changePaymentRoute(facade.cardNumberTarget(opened)); await settle();
    expect(confirm).not.toHaveBeenCalled(); expect(facade.paymentRouteChanging()).toBe(true);
    expect(picker.request()).toEqual(expect.objectContaining({ heading: 'Заказ №1', currentRecipient: 'Лидия З.', amountKopecks: null }));
    expect(picker.request().autoHint).toContain('затем специалиста заказа, затем менеджера');
    const loaded = await picker.request().loadRecipients();
    expect(api.getManagerOrderPaymentRouteRecipients).toHaveBeenCalledWith(1);
    expect(loaded.amountKopecks).toBe(150_000); expect(loaded.recipients.map(person => person.profileId)).toEqual([44]);
    expect(await picker.request().issue({ profileId: 44 })).toEqual({ status: 'issued' });
    expect(api.changeManagerOrderPaymentRoute).toHaveBeenCalledWith(1, { expectedPaymentLinkId: 71, target: 'EMPLOYEE_REQUISITES', confirmedUnpaid: true, expectedTargetPaymentProfileId: null, recipientProfileId: 44 });
    expect(facade.paymentRouteVisible()).toBe(false); expect(reload).toHaveBeenCalledTimes(1);
    picker.close({ status: 'issued' }); await operation;
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('новый счёт с номером карты по заказу #1') }));
    expect(facade.paymentRouteChanging()).toBe(false);
  });
  it('passes the card of the owner to the window and names it by its own id', async () => {
    const { facade, api, pickRecipient } = create(); const picker = openWindow(pickRecipient);
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of(bankLink()));
    api.getManagerOrderPaymentRouteRecipients.mockReturnValueOnce(of({ ...people(), ownerCard: { name: ' Мия Р. ', remainingKopecks: 4_500_000, current: false, blockReason: '' } }));
    api.changeManagerOrderPaymentRoute.mockReturnValue(of({ clientNotificationScheduled: true }));
    await facade.openPaymentRouteChange();
    const operation = facade.changePaymentRoute(facade.cardNumberTarget(facade.paymentRouteContext()!)); await settle();
    expect(picker.request().autoHintWithOwnerCard).toContain('клиент получит карту владельца');
    const loaded = await picker.request().loadRecipients();
    expect(loaded.ownerCard).toEqual({ name: 'Мия Р.', remainingKopecks: 4_500_000, current: false, blockReason: null });
    expect(await picker.request().issue({ profileId: -1 })).toEqual({ status: 'issued' });
    expect(api.changeManagerOrderPaymentRoute).toHaveBeenCalledWith(1, { expectedPaymentLinkId: 71, target: 'EMPLOYEE_REQUISITES', confirmedUnpaid: true, expectedTargetPaymentProfileId: null, recipientProfileId: -1 });
    picker.close({ status: 'issued' }); await operation;
  });
  it('lets the system choose another recipient when no person is named', async () => {
    const { facade, api, pickRecipient } = create(); const picker = openWindow(pickRecipient);
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of({ ...context(), configuredMode: 'AUTO_ROUTING', currentRoute: 'Номер карты' } as PaymentRouteChangeContext));
    api.changeManagerOrderPaymentRoute.mockReturnValue(of({ clientNotificationScheduled: true }));
    await facade.openPaymentRouteChange(); const opened = facade.paymentRouteContext()!;
    expect(facade.cardNumberTarget(opened)).toBe('DIFFERENT_RECIPIENT');
    const operation = facade.changePaymentRoute('DIFFERENT_RECIPIENT'); await settle();
    expect(picker.request().autoHint).toContain('Нынешний получатель исключается');
    await picker.request().issue({ profileId: null });
    const sent = api.changeManagerOrderPaymentRoute.mock.calls[0][1];
    expect(sent).toEqual({ expectedPaymentLinkId: 71, target: 'DIFFERENT_RECIPIENT', confirmedUnpaid: true, expectedTargetPaymentProfileId: null });
    expect(Object.keys(sent)).not.toContain('recipientProfileId');
    picker.close({ status: 'issued' }); await operation;
  });
  it('keeps the window open with the refusal and reports a request the bank still has to finish', async () => {
    const { facade, api, pickRecipient, reload, toast, error } = create(); const picker = openWindow(pickRecipient);
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of(bankLink()));
    api.changeManagerOrderPaymentRoute.mockReturnValueOnce(throwError(() => ({ status: 409 }))).mockReturnValueOnce(throwError(() => ({ status: 423 })));
    await facade.openPaymentRouteChange(); const operation = facade.changePaymentRoute('EMPLOYEE_REQUISITES'); await settle();
    expect(await picker.request().issue({ profileId: 44 })).toEqual({ status: 'refused', message: 'Не удалось выдать номер карты' });
    expect(facade.paymentRouteVisible()).toBe(true); expect(error()).toBeNull(); expect(reload).not.toHaveBeenCalled();
    const accepted = await picker.request().issue({ profileId: null });
    expect(accepted).toEqual({ status: 'accepted', message: expect.stringContaining('Запрос принят') });
    expect(facade.paymentRouteVisible()).toBe(false);
    picker.close(accepted as MobilePaymentRecipientPickerResult); await operation;
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('Запрос принят') }));
    expect(reload).not.toHaveBeenCalled(); expect(facade.paymentRouteChanging()).toBe(false);
  });
  it.each([
    ['an order without an issued invoice', { paymentLinkId: null, configuredMode: 'AUTO_ROUTING' }],
    ['a withdrawn paper invoice', { paperInvoiceIssued: true, configuredMode: 'OWNER_PAPER_INVOICE' }]
  ])('keeps the confirmation and opens no window for %s', async (_, patch) => {
    const { facade, api, confirm, pickRecipient } = create();
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of({ ...context(), ...patch } as PaymentRouteChangeContext));
    api.changeManagerOrderPaymentRoute.mockReturnValue(of({ clientNotificationScheduled: false }));
    await facade.openPaymentRouteChange(); expect(facade.cardNumberHint(facade.paymentRouteContext()!)).not.toContain('Откроется окно');
    await facade.changePaymentRoute('EMPLOYEE_REQUISITES');
    expect(pickRecipient).not.toHaveBeenCalled(); expect(confirm).toHaveBeenCalledTimes(1);
    expect(api.changeManagerOrderPaymentRoute).toHaveBeenCalledTimes(1);
    expect(Object.keys(api.changeManagerOrderPaymentRoute.mock.calls[0][1])).not.toContain('recipientProfileId');
  });
  it('offers the card number mode that is already set again only once an invoice is issued, to name a person', async () => {
    const { facade, api, confirm, pickRecipient } = create();
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of({ ...context(), paymentLinkId: null } as PaymentRouteChangeContext));
    await facade.openPaymentRouteChange();
    expect(facade.paymentRouteTargetActive('EMPLOYEE_REQUISITES')).toBe(true);
    await facade.changePaymentRoute('EMPLOYEE_REQUISITES');
    expect(confirm).not.toHaveBeenCalled(); expect(pickRecipient).not.toHaveBeenCalled();

    facade.closePaymentRouteChange();
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of({ ...context(), bankRecipientChangeAvailable: true } as PaymentRouteChangeContext));
    await facade.openPaymentRouteChange(); const opened = facade.paymentRouteContext()!;
    expect(facade.cardNumberTarget(opened)).toBe('EMPLOYEE_REQUISITES'); expect(facade.paymentRouteTargetActive('EMPLOYEE_REQUISITES')).toBe(false);
    await facade.changePaymentRoute('EMPLOYEE_REQUISITES');
    expect(pickRecipient).toHaveBeenCalledTimes(1); expect(confirm).not.toHaveBeenCalled();
    expect(api.changeManagerOrderPaymentRoute).not.toHaveBeenCalled();
  });
  it('closes the window on leave, never sends a choice made after it and ignores a late answer', async () => {
    const { facade, api, pickRecipient, reload, toast, route, env } = create(); const picker = openWindow(pickRecipient);
    const mutation = new Subject<{ clientNotificationScheduled: boolean }>(); api.changeManagerOrderPaymentRoute.mockReturnValue(mutation);
    api.getManagerOrderPaymentRouteChangeContext.mockReturnValueOnce(of(bankLink()));
    await facade.openPaymentRouteChange(); const operation = facade.changePaymentRoute('EMPLOYEE_REQUISITES'); await settle();
    const list = new Subject<PaymentRouteRecipientsResponse>(); api.getManagerOrderPaymentRouteRecipients.mockReturnValueOnce(list);
    const loading = picker.request().loadRecipients(); expect(list.observed).toBe(true);
    const dispatched = picker.request().issue({ profileId: 44 }); await settle();
    expect(api.changeManagerOrderPaymentRoute).toHaveBeenCalledTimes(1);
    facade.deactivate(); env.id = 2; route.change('order:2');
    expect(picker.request().signal?.aborted).toBe(true); expect(list.observed).toBe(false); expect(mutation.observed).toBe(true);
    expect((await loading).recipients).toEqual([]);
    expect(await picker.request().issue({ profileId: null })).toEqual({ status: 'left' });
    expect(api.changeManagerOrderPaymentRoute).toHaveBeenCalledTimes(1);
    mutation.next({ clientNotificationScheduled: true }); mutation.complete();
    expect(await dispatched).toEqual({ status: 'left' });
    picker.close(null); await operation;
    expect(reload).not.toHaveBeenCalled(); expect(toast).not.toHaveBeenCalled();
  });
});
