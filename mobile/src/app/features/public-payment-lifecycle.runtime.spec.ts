import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, NavigationStart, NavigationCancel, NavigationError, NavigationSkipped, NavigationEnd, convertToParamMap } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BehaviorSubject, Subject } from 'rxjs';
import { PublicPayPage } from './public-pay.page';
import { PublicPayGroupPage } from './public-pay-group.page';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import type { PublicCommonInvoice } from '@otziv/client-common/public-payments';

const payment = (token = 'A', manual = false) => ({
  token, companyTitle: '', filialTitle: '', serviceTitle: '', amount: 10, amountKopecks: 1000,
  description: '', status: 'CREATED', expiresAt: null, payable: true, sbpBankSelectionSupported: false,
  paymentMethod: manual ? 'MANUAL_MOBILE_BANK' : 'BANK_FORM', manualPhone: '+79990000000'
});
const invoice = (token = 'A', manual = false): PublicCommonInvoice => ({
  token, title: 'Invoice', accountName: '', status: 'INVOICED', payable: true, clientReportable: true, orders: [],
  amount: 10, paid: 0, remaining: 10, amountKopecks: 1000, paidKopecks: 0, remainingKopecks: 1000,
  paymentRouteType: manual ? 'MANUAL_MOBILE_BANK' : 'BANK_LINK'
});

describe('cached Ionic public payment pages', () => {
  let router: { events: Subject<unknown>; url: string };
  let requests: HttpTestingController;
  let route: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let openPayment: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    router = { events: new Subject(), url: '/pay/A' };
    route = new BehaviorSubject(convertToParamMap({ token: 'A' }));
    openPayment = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: Router, useValue: router },
      { provide: ActivatedRoute, useValue: { paramMap: route } },
      { provide: MobileExternalLinkService, useValue: { openPayment } }
    ] });
    for (const type of [PublicPayPage, PublicPayGroupPage]) {
      TestBed.overrideComponent(type, { set: { template: '', imports: [] } });
    }
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => requests.verify());

  for (const scenario of ['bank', 'sbp', 'manual', 'group-bank', 'group-manual']) {
    for (const reenter of [false, true]) {
      it(`${scenario}: ignores a completed write after leaving${reenter ? ' and reentering the same token' : ''}, without cancelling or replaying it`, () => {
        const group = scenario.startsWith('group');
        const manual = scenario.endsWith('manual');
        const fixture = group ? TestBed.createComponent(PublicPayGroupPage) : TestBed.createComponent(PublicPayPage);
        const page = fixture.componentInstance;
        const url = `/api/payments/public/${group ? 'group/' : ''}A`;
        const data = group ? invoice('A', manual) : payment('A', manual);
        requests.expectOne(url).flush(data);
        // First Ionic enter must not duplicate the constructor's initial read.
        page.ionViewWillEnter();
        requests.expectNone(url);
        page.email.set('payer@example.test');
        page.offerConsent.set(true); page.privacyConsent.set(true); page.receiptConsent.set(true);
        if (page instanceof PublicPayGroupPage) {
          if (manual) page.reportPaid(); else page.submitPayment();
        } else if (manual) page.reportManualPayment();
        else if (scenario === 'sbp') page.submitSbp();
        else page.submitBankForm();
        const command = requests.expectOne(request => request.method === 'POST');

        page.ionViewWillLeave(); // The retained fixture is deliberately not destroyed.
        page.onWindowFocus(); page.onPageShow();
        requests.expectNone(request => request.method === 'GET');
        expect(command.cancelled).toBe(false);
        if (reenter) {
          page.ionViewWillEnter();
          requests.expectOne(url).flush(data);
          requests.expectNone(request => request.method === 'POST');
        }
        command.flush(manual
          ? { ...data, status: 'MANUAL_REPORTED', clientReportedAt: '2026-09-07T12:00:00' }
          : { status: 'NEW', paymentId: 'fixture-payment', paymentUrl: 'https://securepay.tinkoff.ru/browser-fixture', qrPayload: 'https://qr.nspk.ru/fixture' });

        expect(openPayment).not.toHaveBeenCalled();
        expect(page.message()).toBe('');
        expect(page.error()).toBe('');
        expect(page instanceof PublicPayGroupPage ? page.invoice()?.status : page.payment()?.status)
          .toBe(data.status);
        requests.expectNone(request => request.method === 'POST');
        fixture.destroy();
      });
    }
  }

  for (const group of [false, true]) {
    it(`${group ? 'group' : 'single'}: cancels a read on leave and defers hidden token changes until entry`, () => {
      const fixture = group ? TestBed.createComponent(PublicPayGroupPage) : TestBed.createComponent(PublicPayPage);
      const page = fixture.componentInstance;
      const base = `/api/payments/public/${group ? 'group/' : ''}`;
      const first = requests.expectOne(base + 'A');
      page.ionViewWillLeave();
      expect(first.cancelled).toBe(true);
      route.next(convertToParamMap({ token: 'B' }));
      page.onWindowFocus();
      requests.expectNone(request => request.method === 'GET');

      page.ionViewWillEnter();
      requests.expectOne(base + 'B').flush(group ? invoice('B') : payment('B'));
      expect(page.token()).toBe('B');
      requests.expectNone(request => request.method === 'POST');
      fixture.destroy();
    });
  }

  for (const group of [false, true]) {
    for (const result of ['leave', 'cancel', 'error', 'skip', 'redirect-back']) {
      it(`${group ? 'group' : 'single'}: invalidates navigation before Ionic willLeave and handles ${result} without reviving the old write`, () => {
        router.url = group ? '/pay/group/A' : '/pay/A';
        const fixture = group ? TestBed.createComponent(PublicPayGroupPage) : TestBed.createComponent(PublicPayPage);
        const page = fixture.componentInstance;
        const url = `/api/payments/public/${group ? 'group/' : ''}A`;
        const data = group ? invoice() : payment();
        requests.expectOne(url).flush(data);
        page.email.set('payer@example.test');
        page.offerConsent.set(true); page.privacyConsent.set(true); page.receiptConsent.set(true);
        if (page instanceof PublicPayGroupPage) page.submitPayment(); else page.submitBankForm();
        const command = requests.expectOne(request => request.method === 'POST');
        // Angular has begun leaving; Ionic's async page transition has not emitted willLeave.
        router.events.next(new NavigationStart(2, '/offer'));
        expect(command.cancelled).toBe(false);
        requests.expectNone(request => request.method === 'GET');
        if (result === 'cancel') router.events.next(new NavigationCancel(2, '/offer', 'guard'));
        else if (result === 'error') router.events.next(new NavigationError(2, '/offer', new Error('lazy chunk failed')));
        else if (result === 'skip') router.events.next(new NavigationSkipped(2, router.url, 'same URL'));
        else if (result === 'redirect-back') router.events.next(new NavigationEnd(2, '/offer', router.url));
        else router.url = '/offer';
        if (result !== 'leave') {
          requests.expectOne(url).flush(data);
          expect(page.offerConsent()).toBe(false);
        }
        command.flush({ status: 'NEW', paymentId: 'fixture-payment', paymentUrl: 'https://securepay.tinkoff.ru/browser-fixture' });
        expect(openPayment).not.toHaveBeenCalled();
        requests.expectNone(request => request.method === 'POST');
        fixture.destroy();
      });
    }
  }

  it('rejects an old bank error after the same cached page is shown again', () => {
    const fixture = TestBed.createComponent(PublicPayPage);
    const page = fixture.componentInstance;
    requests.expectOne('/api/payments/public/A').flush(payment());
    page.email.set('payer@example.test');
    page.offerConsent.set(true); page.privacyConsent.set(true); page.receiptConsent.set(true);
    page.submitBankForm();
    const command = requests.expectOne(request => request.method === 'POST');
    page.ionViewWillLeave(); page.ionViewWillEnter();
    requests.expectOne('/api/payments/public/A').flush(payment());
    command.flush({ message: 'Previous attempt failed' }, { status: 504, statusText: 'Gateway Timeout' });
    expect(page.error()).toBe('');
    expect(page.bankSubmitting()).toBe(false);
    requests.expectNone(request => request.method === 'POST');
    fixture.destroy();
  });
});
