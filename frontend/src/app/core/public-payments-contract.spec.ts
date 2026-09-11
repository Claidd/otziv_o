import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PaymentsApi } from './payments.api';

const invoice = () => ({ token: 'a/b', title: 'Invoice', accountName: '', status: 'READY', amount: 100, paid: 0, remaining: 100,
  amountKopecks: 10_000, paidKopecks: 0, remainingKopecks: 10_000, payable: true, clientReportable: true,
  paymentRouteType: 'BANK_LINK', manualPhone: null, orders: [] });

describe('public payments generated boundary and presentation projection', () => {
  let requests: HttpTestingController;
  let api: PaymentsApi;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    requests = TestBed.inject(HttpTestingController); api = TestBed.inject(PaymentsApi);
  });
  afterEach(() => requests.verify());
  it('preserves nullable optional invoice data and server amounts while unknown status disables actions', async () => {
    const result = firstValueFrom(api.getPublicCommonInvoice('a/b'));
    requests.expectOne('/api/payments/public/group/a%2Fb').flush({ ...invoice(), status: 'FUTURE_STATE' });
    const value = await result;
    expect(value.status).toBe('FUTURE_STATE'); expect(value.payable).toBe(false); expect(value.clientReportable).toBe(false);
    expect(value.amountKopecks).toBe(10_000); expect(value.manualPhone).toBeNull();
  });
  it('validates reported group invoice response once and disables an unknown route', async () => {
    const result = firstValueFrom(api.reportPublicCommonInvoicePaid('a/b'));
    const request = requests.expectOne('/api/payments/public/group/a%2Fb/reported-paid');
    expect(request.request.method).toBe('POST'); expect(request.request.body).toEqual({});
    request.flush({ ...invoice(), paymentRouteType: 'FUTURE_ROUTE' });
    const value = await result; expect(value.payable).toBe(false); expect(value.clientReportable).toBe(false);
  });
  it('preserves all initiation payloads and QR-only nullable responses', async () => {
    const cases = [
      { start: () => api.initPublicPayment('a/b', 'test@example.invalid', true, false, true), suffix: '/a%2Fb/init', bank: false },
      { start: () => api.initPublicCommonInvoicePayment('a/b', 'test@example.invalid', true, false, true), suffix: '/group/a%2Fb/init', bank: false },
      { start: () => api.initPublicSbpPayment('a/b', 'test@example.invalid', true, false, true, 'bank-1'), suffix: '/a%2Fb/sbp', bank: true }
    ];
    for (const current of cases) {
      const result = firstValueFrom(current.start()); const request = requests.expectOne(`/api/payments/public${current.suffix}`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ email: 'test@example.invalid', offerConsent: true, privacyConsent: false, receiptConsent: true, ...(current.bank ? { sbpBankId: 'bank-1' } : {}) });
      const response = { paymentUrl: null, paymentId: null, status: 'FUTURE_STATUS', method: null, qrPayload: 'bank://fixture', qrImage: null };
      request.flush(response); expect(await result).toEqual(response);
    }
  });
  it('does not replay writes after a timeout or 409', async () => {
    for (const failure of ['timeout', 'conflict']) {
      const result = firstValueFrom(api.initPublicPayment('token', 'test@example.invalid', true, true, true));
      const assertion = expect(result).rejects.toMatchObject({ status: failure === 'timeout' ? 0 : 409 });
      const request = requests.expectOne('/api/payments/public/token/init');
      if (failure === 'timeout') request.error(new ProgressEvent('error'));
      else request.flush({ code: 'PAYMENT_ROUTE_STALE' }, { status: 409, statusText: 'Conflict' });
      await assertion; requests.expectNone('/api/payments/public/token/init');
    }
  });
  it('keeps read cancellation and nullable SBP bank metadata', async () => {
    const subscription = api.getPublicSbpBanks('old').subscribe();
    const old = requests.expectOne('/api/payments/public/old/sbp/banks'); subscription.unsubscribe(); expect(old.cancelled).toBe(true);
    const result = firstValueFrom(api.getPublicSbpBanks('current'));
    const banks = [{ bankId: 'bank-1', name: 'Bank', featured: false, nspkBankId: null, logoUrl: null }];
    requests.expectOne('/api/payments/public/current/sbp/banks').flush(banks); expect(await result).toEqual(banks);
  });
  it('applies the same unknown-state protection to manual-payment write responses', async () => {
    const result = firstValueFrom(api.reportPublicManualPayment('token'));
    const request = requests.expectOne('/api/payments/public/token/manual-paid');
    expect(request.request.method).toBe('POST');
    request.flush({ token: 'token', companyTitle: '', filialTitle: '', serviceTitle: '', amount: 10, amountKopecks: 1000,
      description: '', status: 'FUTURE', expiresAt: null, payable: true });
    expect((await result).payable).toBe(false);
  });
});
