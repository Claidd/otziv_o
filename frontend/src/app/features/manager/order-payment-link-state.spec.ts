import { Observable, Subject, of, throwError } from 'rxjs';
import type { ManagerPaymentLinkResponse, TbankPaymentStatus } from '../../core/payments.api';
import { OrderPaymentLinkState } from './order-payment-link-state';

function status(): TbankPaymentStatus {
  return { enabled: true, paymentLinksEnabled: true, managerUiEnabled: true, applyConfirmedPayments: true, hasCredentials: true,
    testMode: false, runtimeMode: 'LIVE', baseUrl: '', publicBaseUrl: '', notificationUrl: '', successUrl: '', failUrl: '' };
}
function link(orderId: number): ManagerPaymentLinkResponse {
  return { token: `token-${orderId}`, url: `https://fixture.invalid/${orderId}`, orderId, amount: 100, amountKopecks: 10_000,
    status: 'CREATED', expiresAt: '', copyText: `Invoice ${orderId}` };
}
function fixture() {
  const api = { getTbankStatus: vi.fn(() => of(status())), createOrderPaymentLink: vi.fn() };
  const effects = { started: vi.fn(), ready: vi.fn(), failed: vi.fn() };
  return { api, effects, state: new OrderPaymentLinkState(api, effects) };
}

describe('order payment link scenario', () => {
  it('loads protected status only with permission, once, and retains mode labels', () => {
    const { state, api } = fixture();
    expect(state.modeLabel()).toBe('Проверка'); state.initialize(false); expect(api.getTbankStatus).not.toHaveBeenCalled();
    state.initialize(true); state.initialize(true); expect(api.getTbankStatus).toHaveBeenCalledOnce();
    expect(state.modeLabel()).toBe('Автоучёт оплаты');
    state.status.set({ ...status(), applyConfirmedPayments: false }); expect(state.modeLabel()).toBe('Без автоучёта');
    state.status.set({ ...status(), runtimeMode: 'TEST' }); expect(state.modeLabel()).toBe('Тестовый режим'); state.dispose();
  });
  it('creates once while pending, reuses the current link and leaves permissions authoritative', () => {
    const { state, api, effects } = fixture(); const result = new Subject<ManagerPaymentLinkResponse>(); api.createOrderPaymentLink.mockReturnValue(result);
    state.createOrCopy(null, true); state.createOrCopy(1, false); expect(api.createOrderPaymentLink).not.toHaveBeenCalled();
    state.createOrCopy(1, true); state.createOrCopy(1, true); expect(api.createOrderPaymentLink).toHaveBeenCalledExactlyOnceWith(1);
    expect(state.busy()).toBe(true); result.next(link(1)); expect(state.busy()).toBe(false);
    expect(effects.ready).toHaveBeenLastCalledWith('Invoice 1', true, expect.any(Function));
    state.createOrCopy(1, false); expect(effects.ready).toHaveBeenCalledTimes(1);
    state.createOrCopy(1, true); expect(effects.ready).toHaveBeenLastCalledWith('Invoice 1', false, expect.any(Function));
    expect(api.createOrderPaymentLink).toHaveBeenCalledOnce(); state.dispose();
  });
  it('late old-route success cannot replace the current link, stop its loading or copy text', () => {
    const { state, api, effects } = fixture(); const old = new Subject<ManagerPaymentLinkResponse>(), current = new Subject<ManagerPaymentLinkResponse>();
    api.createOrderPaymentLink.mockReturnValueOnce(old).mockReturnValueOnce(current);
    state.createOrCopy(1, true); state.resetRoute(); state.createOrCopy(2, true);
    old.next(link(1)); expect(state.link()).toBeNull(); expect(state.busy()).toBe(true); expect(effects.ready).not.toHaveBeenCalled();
    current.next(link(2)); expect(state.link()?.orderId).toBe(2); expect(state.busy()).toBe(false);
    expect(effects.ready).toHaveBeenCalledExactlyOnceWith('Invoice 2', true, expect.any(Function)); state.dispose();
  });
  it('returning to the same ID does not revive old errors and failures never replay a write', () => {
    const { state, api, effects } = fixture(); const old = new Subject<ManagerPaymentLinkResponse>(), current = new Subject<ManagerPaymentLinkResponse>();
    api.createOrderPaymentLink.mockReturnValueOnce(old).mockReturnValueOnce(current);
    state.createOrCopy(1, true); state.resetRoute(); state.resetRoute(); state.createOrCopy(1, true);
    old.error({ status: 409 }); expect(state.error()).toBeNull(); expect(state.busy()).toBe(true); expect(effects.failed).not.toHaveBeenCalled();
    current.error({ status: 0 }); expect(state.busy()).toBe(false); expect(state.error()).toContain('сервер не отвечает');
    expect(effects.failed).toHaveBeenCalledOnce(); expect(api.createOrderPaymentLink).toHaveBeenCalledTimes(2); state.dispose();
  });
  it('disposal tears down reads and writes and prevents late effects or another request', () => {
    const { state, api, effects } = fixture(); const statusTeardown = vi.fn(), writeTeardown = vi.fn();
    api.getTbankStatus.mockReturnValue(new Observable(() => statusTeardown));
    api.createOrderPaymentLink.mockReturnValue(new Observable(() => writeTeardown));
    state.initialize(true); state.createOrCopy(1, true); state.dispose();
    expect(statusTeardown).toHaveBeenCalledOnce(); expect(writeTeardown).toHaveBeenCalledOnce(); expect(state.busy()).toBe(false);
    state.initialize(true); state.createOrCopy(1, true); expect(api.getTbankStatus).toHaveBeenCalledOnce(); expect(api.createOrderPaymentLink).toHaveBeenCalledOnce();
    expect(effects.ready).not.toHaveBeenCalled(); expect(effects.failed).not.toHaveBeenCalled();
  });
  it('a denied status read leaves the capability hidden without polling or mutation', () => {
    const { state, api } = fixture(); api.getTbankStatus.mockReturnValue(throwError(() => ({ status: 403 })));
    state.initialize(true); expect(state.status()).toBeNull(); state.initialize(true);
    expect(api.getTbankStatus).toHaveBeenCalledOnce(); expect(api.createOrderPaymentLink).not.toHaveBeenCalled(); state.dispose();
  });
});
