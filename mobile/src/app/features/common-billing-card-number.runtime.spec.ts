import fixtures from '../../../../contracts/fixtures/client-api-current.json';
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ToastController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { CommonBillingApi } from '../core/common-billing.api';
import {
  ApiService,
  type CommonInvoiceDetailsResponse,
  type CommonInvoicePaymentRouteChangeContextResponse,
  type CommonInvoiceRouteRecipientsResponse,
  type CommonInvoiceSummaryResponse
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileCommonManualPaymentFlowService } from '../shared/mobile-common-manual-payment-flow.service';
import { MobilePaymentRecipientPickerFlowService } from '../shared/mobile-payment-recipient-picker-flow.service';
import type { MobilePaymentRecipientPickerRequest, MobilePaymentRecipientPickerResult } from '../shared/payment-recipient-picker';
import { CommonBillingPage } from './common-billing.page';

const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const details = (route: Partial<CommonInvoiceSummaryResponse> = {}) => ({
  ...fixtures.responses.CommonInvoiceDetailsResponseOutput,
  summary: {
    ...fixtures.responses.CommonInvoiceSummaryResponseOutput, id: 41, status: 'INVOICED', lastError: null, invoicePaymentMode: 'AUTO_ROUTING',
    totalOrders: 1, readyOrders: 1, paidOrders: 0, amountKopecks: 250_000, paidKopecks: 100_000,
    paymentRouteType: 'BANK_LINK', paymentRouteProvider: 'TBANK', paymentRouteProfileName: null, paymentRouteRecipient: null, ...route
  },
  orders: [], orderCards: [], delivery: null
}) as unknown as CommonInvoiceDetailsResponse;
const cardNumber = () => details({ paymentRouteType: 'MANUAL_MOBILE_BANK', paymentRouteRecipient: 'Анна П.' });
const context = (patch: Partial<CommonInvoicePaymentRouteChangeContextResponse> = {}) => ({
  currentRoute: 'Эквайринг', currentTarget: 'OWNER_TBANK', currentRecipient: ' Владелец ', status: 'Выставлен', canChange: true,
  blockReason: '', paymentEvidenceToken: 'token-1', canReissueOwnerBank: false, ownerBankReissueBlockReason: '', ...patch
}) as CommonInvoicePaymentRouteChangeContextResponse;
const people = () => ({ invoiceId: 41, amountKopecks: 150_000, recipients: [
  { profileId: 44, name: 'Анна П.', role: 'SPECIALIST', orderSpecialist: false, manager: false, teamMember: true, workedWithClient: false, availableKopecks: 40_000, overrunKopecks: 110_000, current: false, blockReason: null },
  { profileId: 45, name: 'Олег К.', role: 'SPECIALIST', orderSpecialist: true, manager: false, teamMember: false, workedWithClient: false, availableKopecks: 0, overrunKopecks: 0, current: false, blockReason: 'допуск к приёму оплат выключен' }
] }) as CommonInvoiceRouteRecipientsResponse;

describe('«Выдать номер карты» for a common invoice in the app', () => {
  let fixture: ComponentFixture<CommonBillingPage>;
  let page: CommonBillingPage;
  let api: {
    getCommonInvoice: ReturnType<typeof vi.fn>; getCommonManualPaymentMode: ReturnType<typeof vi.fn>;
    getCommonInvoicePaymentRouteChangeContext: ReturnType<typeof vi.fn>; getCommonInvoicePaymentRouteRecipients: ReturnType<typeof vi.fn>;
    changeCommonInvoicePaymentRoute: ReturnType<typeof vi.fn>;
  };
  let confirm: ReturnType<typeof vi.fn>;
  let toast: { create: ReturnType<typeof vi.fn> };
  let request: MobilePaymentRecipientPickerRequest | undefined;
  let closeWindow: (result: MobilePaymentRecipientPickerResult | null) => void;
  let destroyed: boolean;

  beforeEach(async () => {
    destroyed = false; request = undefined;
    api = {
      getCommonInvoice: vi.fn(() => of(details())), getCommonManualPaymentMode: vi.fn(() => of({ attributionRequired: false })),
      getCommonInvoicePaymentRouteChangeContext: vi.fn(() => of(context())), getCommonInvoicePaymentRouteRecipients: vi.fn(() => of(people())),
      changeCommonInvoicePaymentRoute: vi.fn(() => of(cardNumber()))
    };
    confirm = vi.fn().mockResolvedValue(true);
    toast = { create: vi.fn().mockResolvedValue({ present: vi.fn().mockResolvedValue(undefined) }) };
    TestBed.configureTestingModule({ providers: [
      { provide: CommonBillingApi, useValue: api }, { provide: ApiService, useValue: {} },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => true } },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ invoiceId: '41' })) } },
      { provide: Router, useValue: {} }, { provide: MobileConfirmService, useValue: { confirm } },
      { provide: MobileCommonManualPaymentFlowService, useValue: {} }, { provide: ToastController, useValue: toast },
      // A window that stays open: the test presses its buttons through the captured request.
      { provide: MobilePaymentRecipientPickerFlowService, useValue: { open: (value: MobilePaymentRecipientPickerRequest) => {
        request = value; return new Promise<MobilePaymentRecipientPickerResult | null>(resolve => closeWindow = resolve);
      } } }
    ] });
    TestBed.overrideComponent(CommonBillingPage, { set: { template: '', imports: [] } });
    fixture = TestBed.createComponent(CommonBillingPage); page = fixture.componentInstance;
    page.ngOnInit(); await settle();
  });
  afterEach(() => { if (!destroyed) fixture.destroy(); });

  it('opens the window instead of a confirmation and issues the number of the person the manager named', async () => {
    const operation = page.changePaymentRoute(page.cardNumberRouteTarget(page.summary()?.paymentRouteType)); await settle();
    expect(confirm).not.toHaveBeenCalled(); expect(page.mutating()).toBe('change-payment-route');
    expect(request).toEqual(expect.objectContaining({ heading: 'Общий счёт №41', amountKopecks: 150_000, currentRecipient: 'Владелец' }));
    expect(request!.autoHint).toContain('затем специалиста заказа, затем менеджера');
    const loaded = await request!.loadRecipients();
    expect(api.getCommonInvoicePaymentRouteRecipients).toHaveBeenCalledExactlyOnceWith(41);
    expect(loaded.recipients.map(person => [person.profileId, person.blockReason])).toEqual([[44, null], [45, 'допуск к приёму оплат выключен']]);

    expect(await request!.issue({ profileId: 44 })).toEqual({ status: 'issued' });
    expect(api.changeCommonInvoicePaymentRoute).toHaveBeenCalledExactlyOnceWith(41, 'EMPLOYEE_REQUISITES', 'token-1', undefined, 44);
    expect(page.summary()?.paymentRouteRecipient).toBe('Анна П.');
    closeWindow({ status: 'issued' }); await operation;
    expect(toast.create).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ message: 'Получатель изменён: Т-Банк владельца → Реквизиты · Анна П.' }));
    expect(page.mutating()).toBeNull(); expect(page.error()).toBeNull();
  });

  it('passes the card of the owner to the window and names it by its own id', async () => {
    api.getCommonInvoicePaymentRouteRecipients.mockReturnValueOnce(of({ ...people(), ownerCard: { name: ' Мия Р. ', remainingKopecks: 4_500_000, current: false, blockReason: '' } }));
    const operation = page.changePaymentRoute(page.cardNumberRouteTarget(page.summary()?.paymentRouteType)); await settle();
    expect(request!.autoHintWithOwnerCard).toContain('клиент получит карту владельца');
    const loaded = await request!.loadRecipients();
    expect(loaded.ownerCard).toEqual({ name: 'Мия Р.', remainingKopecks: 4_500_000, current: false, blockReason: null });
    expect(await request!.issue({ profileId: -1 })).toEqual({ status: 'issued' });
    expect(api.changeCommonInvoicePaymentRoute).toHaveBeenCalledExactlyOnceWith(41, 'EMPLOYEE_REQUISITES', 'token-1', undefined, -1);
    closeWindow({ status: 'issued' }); await operation;
  });

  it('lets the system choose another person for an invoice that already has a card number', async () => {
    const operation = page.changePaymentRoute('DIFFERENT_RECIPIENT'); await settle();
    expect(request!.autoHint).toContain('Нынешний получатель исключается');
    await request!.issue({ profileId: null });
    expect(api.changeCommonInvoicePaymentRoute).toHaveBeenCalledExactlyOnceWith(41, 'DIFFERENT_RECIPIENT', 'token-1', undefined, null);
    closeWindow({ status: 'issued' }); await operation;
  });

  it('keeps the window open with the refusal and then reports a request the bank still has to finish', async () => {
    api.changeCommonInvoicePaymentRoute
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'У сотрудника выключен допуск к приёму оплат' } })))
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 423, error: { message: 'Запрос принят. Ожидаем ответ банка.' } })));
    const operation = page.changePaymentRoute('EMPLOYEE_REQUISITES'); await settle();
    expect(await request!.issue({ profileId: 44 })).toEqual({ status: 'refused', message: 'У сотрудника выключен допуск к приёму оплат' });
    expect(page.mutating()).toBe('change-payment-route'); expect(page.error()).toBeNull();
    expect(page.summary()?.paymentRouteType).toBe('BANK_LINK'); expect(toast.create).not.toHaveBeenCalled();

    const accepted = await request!.issue({ profileId: null });
    expect(accepted).toEqual({ status: 'accepted', message: 'Запрос принят. Ожидаем ответ банка.' });
    closeWindow(accepted as MobilePaymentRecipientPickerResult); await operation;
    expect(toast.create).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ message: 'Запрос принят. Ожидаем ответ банка.' }));
    expect(page.mutating()).toBeNull(); expect(page.error()).toBeNull();
  });

  it('says that the request was accepted when the server answers with the unchanged route', async () => {
    api.changeCommonInvoicePaymentRoute.mockReturnValue(of(details()));
    const operation = page.changePaymentRoute('EMPLOYEE_REQUISITES'); await settle();
    expect(await request!.issue({ profileId: null })).toEqual({ status: 'issued' });
    closeWindow({ status: 'issued' }); await operation;
    expect(toast.create).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ message: expect.stringContaining('когда банк подтвердит закрытие прежней ссылки') }));
  });

  it('changes nothing when the window is closed and keeps the confirmation for the bank link', async () => {
    const cancelled = page.changePaymentRoute('EMPLOYEE_REQUISITES'); await settle();
    closeWindow(null); await cancelled;
    expect(api.changeCommonInvoicePaymentRoute).not.toHaveBeenCalled(); expect(toast.create).not.toHaveBeenCalled();
    expect(page.mutating()).toBeNull();

    request = undefined; api.changeCommonInvoicePaymentRoute.mockReturnValue(of(details()));
    api.getCommonInvoicePaymentRouteChangeContext.mockReturnValue(of(context({ currentTarget: 'EMPLOYEE_REQUISITES' })));
    await page.changePaymentRoute('OWNER_TBANK');
    expect(request).toBeUndefined(); expect(confirm).toHaveBeenCalledTimes(1);
    expect(api.changeCommonInvoicePaymentRoute).toHaveBeenCalledExactlyOnceWith(41, 'OWNER_TBANK', 'token-1');
  });

  it('closes the window when the invoice screen is left and never sends a choice made after it', async () => {
    const operation = page.changePaymentRoute('EMPLOYEE_REQUISITES'); await settle();
    expect(request!.signal?.aborted).toBe(false);
    fixture.destroy(); destroyed = true;
    expect(request!.signal?.aborted).toBe(true);
    expect(await request!.issue({ profileId: 44 })).toEqual({ status: 'left' });
    expect(api.changeCommonInvoicePaymentRoute).not.toHaveBeenCalled();
    closeWindow(null); await operation;
    expect(toast.create).not.toHaveBeenCalled();
  });
});
