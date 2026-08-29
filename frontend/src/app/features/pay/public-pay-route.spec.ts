import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of } from 'rxjs';
import {
  PaymentsApi,
  PublicCommonInvoice,
  PublicPaymentInitResponse,
  PublicPaymentLink
} from '../../core/payments.api';
import { PayGroupPageComponent } from './pay-group-page.component';
import { PayPageComponent } from './pay-page.component';

describe('public payment route isolation', () => {
  afterEach(() => vi.restoreAllMocks());

  it('single payment cancels stale reads and suppresses a late init navigation without cancelling the write', async () => {
    const params = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    const first = new Subject<PublicPaymentLink>();
    const second = new Subject<PublicPaymentLink>();
    const firstTeardown = vi.fn();
    const mutation = new Subject<PublicPaymentInitResponse>();
    const mutationTeardown = vi.fn();
    const paymentsApi = {
      getPublicPaymentLink: vi.fn()
        .mockReturnValueOnce(withTeardown(first, firstTeardown))
        .mockReturnValueOnce(second)
        .mockReturnValueOnce(of(payment('C'))),
      getPublicSbpBanks: vi.fn(() => of([])),
      initPublicPayment: vi.fn(() => withTeardown(mutation, mutationTeardown)),
      initPublicSbpPayment: vi.fn(),
      reportPublicManualPayment: vi.fn()
    };
    await configure(PayPageComponent, params, paymentsApi);
    const fixture = TestBed.createComponent(PayPageComponent);
    const component = fixture.componentInstance;

    component.email.set('draft@example.com');
    params.next(convertToParamMap({ token: 'B' }));

    expect(firstTeardown).toHaveBeenCalledOnce();
    expect(component.token()).toBe('B');
    expect(component.payment()).toBeNull();
    expect(component.email()).toBe('');
    first.next(payment('A'));
    second.next(payment('B'));
    expect(component.payment()?.token).toBe('B');

    component.email.set('payer@example.com');
    component.offerConsent.set(true);
    component.privacyConsent.set(true);
    component.receiptConsent.set(true);
    const navigate = vi.spyOn(component as unknown as { navigatePayment: () => boolean }, 'navigatePayment');
    component.submitBankForm();
    params.next(convertToParamMap({ token: 'C' }));

    expect(mutationTeardown).not.toHaveBeenCalled();
    expect(component.payment()?.token).toBe('C');
    expect(component.bankSubmitting()).toBe(false);

    mutation.next({ paymentUrl: 'https://securepay.tinkoff.ru/pay', paymentId: 'p1', status: 'NEW' });
    expect(navigate).not.toHaveBeenCalled();
    expect(component.payment()?.token).toBe('C');

    mutation.complete();
    expect(mutationTeardown).toHaveBeenCalledOnce();
    fixture.destroy();
  });

  it('keeps legacy T-Bank bank selection enabled when the capability is absent', async () => {
    const params = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    const current: PublicPaymentLink = {
      ...payment('A'),
      paymentPageMode: 'SBP_PRIMARY'
    };
    const banks = [{ bankId: '100000000004', name: 'Т-Банк', featured: true }];
    const paymentsApi = {
      getPublicPaymentLink: vi.fn(() => of(current)),
      getPublicSbpBanks: vi.fn(() => of(banks)),
      initPublicPayment: vi.fn(),
      initPublicSbpPayment: vi.fn(),
      reportPublicManualPayment: vi.fn()
    };
    await configure(PayPageComponent, params, paymentsApi);
    const fixture = TestBed.createComponent(PayPageComponent);
    const component = fixture.componentInstance;

    expect(component.sbpBankSelectionSupported()).toBe(true);
    expect(paymentsApi.getPublicSbpBanks).toHaveBeenCalledOnce();
    expect(component.sbpBanks()).toEqual(banks);
    expect(component.selectedSbpBankId()).toBe('100000000004');
    fixture.destroy();
  });

  it('skips the bank picker and opens a safe hosted URL for Tochka SBP', async () => {
    const params = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    const current: PublicPaymentLink = {
      ...payment('A'),
      provider: 'TOCHKA',
      paymentPageMode: 'SBP_PRIMARY',
      sbpBankSelectionSupported: false
    };
    const mutation = new Subject<PublicPaymentInitResponse>();
    const paymentsApi = {
      getPublicPaymentLink: vi.fn(() => of(current)),
      getPublicSbpBanks: vi.fn(() => of([])),
      initPublicPayment: vi.fn(),
      initPublicSbpPayment: vi.fn(() => mutation),
      reportPublicManualPayment: vi.fn()
    };
    await configure(PayPageComponent, params, paymentsApi);
    const fixture = TestBed.createComponent(PayPageComponent);
    const component = fixture.componentInstance;

    expect(component.sbpBankSelectionSupported()).toBe(false);
    expect(paymentsApi.getPublicSbpBanks).not.toHaveBeenCalled();

    component.email.set('payer@example.com');
    component.offerConsent.set(true);
    component.privacyConsent.set(true);
    component.receiptConsent.set(true);
    const navigate = vi.spyOn(
      component as unknown as { navigatePayment: (value: unknown, purpose: string) => boolean },
      'navigatePayment'
    ).mockReturnValue(true);

    component.submitPrimaryPayment();
    expect(paymentsApi.initPublicSbpPayment).toHaveBeenCalledWith(
      'A',
      'payer@example.com',
      true,
      true,
      true,
      null
    );

    const hostedUrl = 'https://merch.securepaytb.ru/order/?uuid=tochka-operation';
    mutation.next({
      paymentUrl: hostedUrl,
      paymentId: 'tochka-operation',
      status: 'CREATED',
      method: 'SBP_QR'
    });

    expect(navigate).toHaveBeenCalledWith(hostedUrl, 'payment');
    expect(component.sbpSubmitting()).toBe(false);
    mutation.complete();
    fixture.destroy();
  });

  it('group payment cancels stale reads and suppresses a late init navigation without cancelling the write', async () => {
    const params = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    const first = new Subject<PublicCommonInvoice>();
    const second = new Subject<PublicCommonInvoice>();
    const firstTeardown = vi.fn();
    const mutation = new Subject<PublicPaymentInitResponse>();
    const mutationTeardown = vi.fn();
    const paymentsApi = {
      getPublicCommonInvoice: vi.fn()
        .mockReturnValueOnce(withTeardown(first, firstTeardown))
        .mockReturnValueOnce(second)
        .mockReturnValueOnce(of(invoice('C'))),
      initPublicCommonInvoicePayment: vi.fn(() => withTeardown(mutation, mutationTeardown)),
      reportPublicCommonInvoicePaid: vi.fn()
    };
    await configure(PayGroupPageComponent, params, paymentsApi);
    const fixture = TestBed.createComponent(PayGroupPageComponent);
    const component = fixture.componentInstance;

    component.email.set('draft@example.com');
    params.next(convertToParamMap({ token: 'B' }));

    expect(firstTeardown).toHaveBeenCalledOnce();
    expect(component.token()).toBe('B');
    expect(component.invoice()).toBeNull();
    expect(component.email()).toBe('');
    first.next(invoice('A'));
    second.next(invoice('B'));
    expect(component.invoice()?.token).toBe('B');

    component.email.set('payer@example.com');
    component.offerConsent.set(true);
    component.privacyConsent.set(true);
    component.receiptConsent.set(true);
    const navigate = vi.spyOn(component as unknown as { navigatePayment: () => boolean }, 'navigatePayment');
    component.submitPayment();
    params.next(convertToParamMap({ token: 'C' }));

    expect(mutationTeardown).not.toHaveBeenCalled();
    expect(component.invoice()?.token).toBe('C');
    expect(component.submitting()).toBe(false);

    mutation.next({ paymentUrl: 'https://securepay.tinkoff.ru/pay', paymentId: 'p2', status: 'NEW' });
    expect(navigate).not.toHaveBeenCalled();
    expect(component.invoice()?.token).toBe('C');

    mutation.complete();
    expect(mutationTeardown).toHaveBeenCalledOnce();
    fixture.destroy();
  });

  it('group contractor route records client report separately from confirmed payment', async () => {
    const params = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    const current: PublicCommonInvoice = {
      ...invoice('A'),
      paymentRouteType: 'MANUAL_MOBILE_BANK',
      clientReportable: true
    };
    const reported = new Subject<PublicCommonInvoice>();
    const paymentsApi = {
      getPublicCommonInvoice: vi.fn(() => of(current)),
      initPublicCommonInvoicePayment: vi.fn(),
      reportPublicCommonInvoicePaid: vi.fn(() => reported)
    };
    await configure(PayGroupPageComponent, params, paymentsApi);
    const fixture = TestBed.createComponent(PayGroupPageComponent);
    const component = fixture.componentInstance;

    component.reportPaid();

    expect(paymentsApi.reportPublicCommonInvoicePaid).toHaveBeenCalledWith('A');
    expect(component.reportingPaid()).toBe(true);
    reported.next({
      ...current,
      clientReportable: false,
      clientReportedAt: '2026-08-07T12:00:00'
    });
    expect(component.clientReported()).toBe(true);
    expect(component.invoice()?.paidKopecks).toBe(0);
    expect(component.reportingPaid()).toBe(false);
    fixture.destroy();
  });

  it('single contractor payment shows card copy for a 16 digit destination', async () => {
    const params = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    const current: PublicPaymentLink = {
      ...payment('A'),
      paymentMethod: 'MANUAL_MOBILE_BANK',
      manualPhone: '2202 2082 3839 6676'
    };
    const paymentsApi = {
      getPublicPaymentLink: vi.fn(() => of(current)),
      getPublicSbpBanks: vi.fn(() => of([])),
      initPublicPayment: vi.fn(),
      initPublicSbpPayment: vi.fn(),
      reportPublicManualPayment: vi.fn()
    };
    await configure(PayPageComponent, params, paymentsApi);
    const fixture = TestBed.createComponent(PayPageComponent);
    const component = fixture.componentInstance;

    expect(component.manualPaymentTitle()).toBe('Оплата по номеру карты');
    expect(component.manualTransferDestinationLabel()).toBe('Номер карты');
    fixture.destroy();
  });

  it('common contractor payment keeps mobile-bank copy for a phone destination', async () => {
    const params = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    const current: PublicCommonInvoice = {
      ...invoice('A'),
      paymentRouteType: 'MANUAL_MOBILE_BANK',
      manualPhone: '+7 (999) 123-45-67'
    };
    const paymentsApi = {
      getPublicCommonInvoice: vi.fn(() => of(current)),
      initPublicCommonInvoicePayment: vi.fn(),
      reportPublicCommonInvoicePaid: vi.fn()
    };
    await configure(PayGroupPageComponent, params, paymentsApi);
    const fixture = TestBed.createComponent(PayGroupPageComponent);
    const component = fixture.componentInstance;

    expect(component.routeTitle()).toBe('Оплата через мобильный банк');
    expect(component.manualTransferDestinationLabel()).toBe('Номер телефона');
    fixture.destroy();
  });
});

async function configure(
  component: typeof PayPageComponent | typeof PayGroupPageComponent,
  params: BehaviorSubject<ReturnType<typeof convertToParamMap>>,
  paymentsApi: object
): Promise<void> {
  TestBed.resetTestingModule();
  await TestBed.configureTestingModule({
    imports: [component],
    providers: [
      { provide: ActivatedRoute, useValue: { paramMap: params } },
      { provide: PaymentsApi, useValue: paymentsApi }
    ]
  })
    .overrideComponent(component, { set: { template: '', imports: [] } })
    .compileComponents();
}

function payment(token: string): PublicPaymentLink {
  return {
    token,
    companyTitle: `Компания ${token}`,
    filialTitle: '',
    serviceTitle: '',
    amount: 100,
    amountKopecks: 10_000,
    description: '',
    status: 'CREATED',
    expiresAt: '2026-08-03T00:00:00Z',
    payable: true,
    paymentPageMode: 'BANK_ONLY'
  };
}

function invoice(token: string): PublicCommonInvoice {
  return {
    token,
    title: `Счёт ${token}`,
    accountName: '',
    status: 'READY',
    amount: 100,
    paid: 0,
    remaining: 100,
    amountKopecks: 10_000,
    paidKopecks: 0,
    remainingKopecks: 10_000,
    payable: true,
    paymentRouteType: 'TBANK_LINK',
    clientReportable: false,
    orders: []
  };
}

function withTeardown<T>(source: Observable<T>, teardown: () => void): Observable<T> {
  return new Observable<T>((subscriber) => {
    const subscription = source.subscribe(subscriber);
    return () => {
      subscription.unsubscribe();
      teardown();
    };
  });
}
