import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EMPTY, of } from 'rxjs';
import { PublicPayPage } from './public-pay.page';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';

const payment = (status = 'CREATED') => ({ token: 'A', companyTitle: '', filialTitle: '', serviceTitle: '', amount: 10, amountKopecks: 1000, description: '', status, expiresAt: null, payable: true, sbpBankSelectionSupported: false });

describe('public payment wire contract in a real mobile component', () => {
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: Router, useValue: { events: EMPTY, url: '/pay/A' } },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ token: 'A' })) } },
      { provide: MobileExternalLinkService, useValue: {} }
    ] });
    TestBed.overrideComponent(PublicPayPage, { set: { template: '', imports: [] } });
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());
  it('does not dispatch either bank command for a future server payment state', () => {
    const fixture = TestBed.createComponent(PublicPayPage);
    const page = fixture.componentInstance;
    requests.expectOne('/api/payments/public/A').flush(payment('FUTURE'));
    page.email.set('payer@example.test'); page.offerConsent.set(true); page.privacyConsent.set(true); page.receiptConsent.set(true);
    expect(page.canSubmit()).toBe(false);
    page.submitSbp(); page.submitBankForm();
    expect(page.payment()?.status).toBe('FUTURE');
    requests.expectNone(request => request.method === 'POST');
    fixture.destroy();
  });
  it('clears cached payable data when a return refresh violates the wire contract', () => {
    const fixture = TestBed.createComponent(PublicPayPage);
    const page = fixture.componentInstance;
    requests.expectOne('/api/payments/public/A').flush(payment());
    expect(page.payment()?.payable).toBe(true);
    page.onWindowFocus();
    requests.expectOne('/api/payments/public/A').flush({ ...payment(), payable: 'true' });
    expect(page.payment()).toBeNull();
    expect(page.canSubmit()).toBe(false);
    expect(page.error()).toContain('Данные платежа изменились');
    fixture.destroy();
  });
});
