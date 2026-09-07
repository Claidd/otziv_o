import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PaymentsApi } from './payments.api';
import { CommonBillingApi } from './common-billing.api';
import { SKIP_AUTH_REDIRECT_ON_401, SKIP_AUTH_TOKEN } from './auth-http-context';

describe('billing/payment read contracts at the Angular HTTP boundary', () => {
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());
  it('keeps the public token encoded and disables an unknown payment state', async () => {
    const result = firstValueFrom(TestBed.inject(PaymentsApi).getPublicPaymentLink('a/b'));
    const request = requests.expectOne('/api/payments/public/a%2Fb');
    expect(request.request.context.get(SKIP_AUTH_TOKEN)).toBe(true);
    expect(request.request.context.get(SKIP_AUTH_REDIRECT_ON_401)).toBe(true);
    request.flush({ token: 'a/b', companyTitle: '', filialTitle: '', serviceTitle: '', amount: 10, amountKopecks: 1000, description: '', status: 'FUTURE', expiresAt: null, payable: true });
    const value = await result;
    expect(value.status).toBe('FUTURE');
    expect(value.payable).toBe(false);
  });
  it('rejects an unknown invoice mode before the financial UI sees it', async () => {
    const result = firstValueFrom(TestBed.inject(CommonBillingApi).accountsForCompany(91));
    const assertion = expect(result).rejects.toThrow('Unsupported invoice payment mode');
    requests.expectOne('/api/common-billing/accounts/by-company/91').flush([{ id: 41, name: 'Account', enabled: true, autoRepeatOrders: true, companies: [], invoicePaymentMode: 'FUTURE' }]);
    await assertion;
  });
});
