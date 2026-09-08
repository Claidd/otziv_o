import { PageWriteTracker } from '../../core/page-write-tracker';
import { signal } from '@angular/core';
import { of, Subject } from 'rxjs';
import { OrderPaymentFacade } from './order-payment.facade';
import { RouteEpochGuard } from '../../core/route-epoch.guard';
import type { PaymentRouteChangeContext, TbankPaymentStatus } from '../../core/api.service';

const context = () => ({ canChange: true, configuredMode: 'EMPLOYEE_REQUISITES', paymentLinkId: 71, expectedTargetPaymentProfileId: 81 }) as PaymentRouteChangeContext;
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

describe('order payment facade', () => {
  const create = () => {
    const route = new RouteEpochGuard(); route.change('order:1'); const env = { id: 1, canManage: true };
    const api = { getManagerOrderPaymentRouteChangeContext: vi.fn(() => of(context())), changeManagerOrderPaymentRoute: vi.fn(), markManagerOrderPaperInvoiceIssued: vi.fn(), createManagerOrderPaymentLink: vi.fn(), getTbankStatus: vi.fn() };
    const confirm = vi.fn().mockResolvedValue(true); const copy = vi.fn().mockResolvedValue(true); const reload = vi.fn(); const toast = vi.fn().mockResolvedValue({ present: vi.fn() });
    const facade = new OrderPaymentFacade({ writes: new PageWriteTracker(), api, orderId: () => env.id, hasDetails: () => true, canManage: () => env.canManage, capture: () => route.capture(), accepts: ticket => route.accepts(ticket), confirm, copy, reload, toast, error: signal<string | null>(null), mutationKey: signal<string | null>(null), errorMessage: (_, fallback) => fallback });
    return { facade, api, confirm, copy, reload, toast, env, route };
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
});
