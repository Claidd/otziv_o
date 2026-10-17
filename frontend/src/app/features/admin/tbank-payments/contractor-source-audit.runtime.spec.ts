import fixtures from '../../../../../../contracts/fixtures/client-api-current.json';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input, output } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { AdminPaymentLinkResponse } from '../../../core/payments.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { MobileBottomPagerComponent } from '../../../shared/mobile/mobile-bottom-pager.component';
import { ToastService } from '../../../shared/toast.service';
import {
  TbankPaymentsComponent,
  parseContractorConfirmedTotalKopecks,
  validContractorAuditEffectiveAt
} from './tbank-payments.component';

const journalUrl = '/api/admin/payments/tbank-links';
const confirmationUrl = '/api/admin/payments/manual-links/8327/contractor-confirmation';
const statementTime = '2026-10-12T07:48';

const contractorLink: AdminPaymentLinkResponse = {
  ...fixtures.responses.AdminPaymentLinkResponseOutput,
  id: 8327,
  orderId: 25322,
  amount: 1400,
  amountKopecks: 140_000,
  confirmedAmountKopecks: 0,
  manualSource: 'CONTRACTOR_PAYMENT_PROFILE',
  paymentMethod: 'MANUAL_MOBILE_BANK',
  manualRecipientName: 'Виктория Ц.',
  status: 'CANCELED',
  archived: false
};

const oldBankLink: AdminPaymentLinkResponse = {
  ...contractorLink,
  id: 8288,
  amount: 1200,
  amountKopecks: 120_000,
  paymentMethod: 'BANK_FORM',
  manualSource: null,
  manualRecipientName: null,
  status: 'EXPIRED'
};

describe('contractor payment source amount', () => {
  it('parses a cumulative amount in exact kopecks', () => {
    expect(parseContractorConfirmedTotalKopecks('1 400,00', 140_000, 0)).toBe(140_000);
    expect(parseContractorConfirmedTotalKopecks('1400.5', 140_100, 0)).toBe(140_050);
    expect(parseContractorConfirmedTotalKopecks('0,01', 140_000, 0)).toBe(1);
  });

  it.each(['', '0', '-1', '1400.001', 'руб. 1400', '1400,00,00', '1400.01'])(
    'rejects invalid or excessive input %s', (value) => {
      expect(parseContractorConfirmedTotalKopecks(value, 140_000, 0)).toBeNull();
    }
  );

  it('refuses to decrease a previously confirmed cumulative total or accept unsafe integers', () => {
    expect(parseContractorConfirmedTotalKopecks('999,99', 140_000, 100_000)).toBeNull();
    expect(parseContractorConfirmedTotalKopecks('1000', 140_000, 100_000)).toBe(100_000);
    expect(parseContractorConfirmedTotalKopecks('90071992547410', Number.MAX_SAFE_INTEGER, 0)).toBeNull();
    expect(parseContractorConfirmedTotalKopecks('1', 0, 0)).toBeNull();
    expect(parseContractorConfirmedTotalKopecks('1', 140_000, 140_001)).toBeNull();
  });
});

describe('contractor payment statement time', () => {
  it('accepts a real recipient wall time independent of browser timezone and rejects impossible times', () => {
    expect(validContractorAuditEffectiveAt(statementTime)).toBe(true);
    expect(validContractorAuditEffectiveAt('2026-09-30T23:59')).toBe(true);
    expect(validContractorAuditEffectiveAt('2026-10-17T23:14')).toBe(true);
    expect(validContractorAuditEffectiveAt('2026-02-30T07:48')).toBe(false);
    expect(validContractorAuditEffectiveAt('2026-13-01T07:48')).toBe(false);
    expect(validContractorAuditEffectiveAt('2026-10-12T24:00')).toBe(false);
    expect(validContractorAuditEffectiveAt('2026-10-12T07:48Z')).toBe(false);
    expect(validContractorAuditEffectiveAt('')).toBe(false);
  });
});

describe('contractor payment source audit through HttpClient', () => {
  let fixture: ComponentFixture<TbankPaymentsComponent>;
  let component: TbankPaymentsComponent;
  let http: HttpTestingController;
  const toast = { error: vi.fn(), success: vi.fn() };

  function paymentPage(items: AdminPaymentLinkResponse[]) {
    return {
      ...fixtures.responses.AdminPaymentLinksPageResponseOutput,
      items,
      page: 0,
      size: 25,
      totalElements: items.length,
      totalPages: 1,
      summary: { ...fixtures.responses.AdminPaymentLinkSummaryResponseOutput, totalElements: items.length }
    };
  }

  function flushReadRequests(items = [contractorLink, oldBankLink]): void {
    for (const request of http.match(candidate => candidate.method === 'GET')) {
      if (request.cancelled) continue;
      const url = request.request.url;
      request.flush(url === journalUrl ? paymentPage(items)
        : url.endsWith('bank-profiles') ? { profiles: [], managers: [] }
        : url.endsWith('manual-tasks') ? []
        : url.includes('monthly-summary') ? { items: [] }
        : {});
    }
  }

  function openContractorAudit(): void {
    component.confirmContractorSource(oldBankLink);
    expect(component.contractorAuditLink()).toBeNull();
    component.confirmContractorSource(contractorLink);
    expect(component.contractorAuditLink()?.id).toBe(8327);
    expect(component.contractorAuditAmount()).toBe('');
    expect(component.contractorAuditEffectiveAt()).toBe('');
    expect(component.contractorAuditStatementChecked()).toBe(false);
  }

  beforeEach(() => {
    toast.error.mockClear();
    toast.success.mockClear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: ToastService, useValue: toast }]
    });
    TestBed.overrideComponent(TbankPaymentsComponent, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TbankPaymentsComponent);
    component = fixture.componentInstance;
    flushReadRequests();
  });

  afterEach(() => {
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });

  it('keeps the old bank link out of the source-specific audit and requires all evidence', () => {
    openContractorAudit();
    component.submitContractorSourceAudit();
    expect(component.contractorAuditError()).toBeTruthy();
    http.expectNone(candidate => candidate.method === 'POST');

    component.contractorAuditAmount.set('1400.001');
    component.contractorAuditReason.set('Выписка получателя 12 октября');
    component.contractorAuditStatementChecked.set(true);
    expect(component.canSubmitContractorAudit()).toBe(false);
    component.submitContractorSourceAudit();
    http.expectNone(candidate => candidate.method === 'POST');

    component.contractorAuditAmount.set('1 400,00');
    component.contractorAuditStatementChecked.set(false);
    expect(component.canSubmitContractorAudit()).toBe(false);
    component.submitContractorSourceAudit();
    http.expectNone(candidate => candidate.method === 'POST');

    component.contractorAuditStatementChecked.set(true);
    component.contractorAuditReason.set(' ');
    expect(component.canSubmitContractorAudit()).toBe(false);
    component.submitContractorSourceAudit();
    http.expectNone(candidate => candidate.method === 'POST');

    component.contractorAuditReason.set('Выписка получателя 12 октября');
    component.contractorAuditEffectiveAt.set('2026-02-30T07:48');
    expect(component.canSubmitContractorAudit()).toBe(false);
    component.submitContractorSourceAudit();
    expect(component.contractorAuditError()).toContain('дату и время');
    http.expectNone(candidate => candidate.method === 'POST');
  });

  it('posts exact source and cumulative kopecks once, then refreshes the journal', () => {
    openContractorAudit();
    component.contractorAuditAmount.set('1 400,00');
    component.contractorAuditEffectiveAt.set(statementTime);
    component.contractorAuditReason.set(' Выписка Виктории за 12 октября ');
    component.contractorAuditStatementChecked.set(true);
    expect(component.canSubmitContractorAudit()).toBe(true);
    component.submitContractorSourceAudit();

    const write = http.expectOne(confirmationUrl);
    expect(write.request.method).toBe('POST');
    expect(write.request.body).toEqual({
      recipientStatementChecked: true,
      paymentReceived: true,
      confirmedTotalKopecks: 140_000,
      effectiveAt: statementTime,
      reason: 'Выписка Виктории за 12 октября'
    });
    component.submitContractorSourceAudit();
    component.closeContractorSourceAudit();
    expect(component.contractorAuditLink()?.id).toBe(8327);
    http.expectNone(candidate => candidate.method === 'POST');

    write.flush({ ...contractorLink, status: 'CONFIRMED', confirmedAmountKopecks: 140_000 });
    expect(component.contractorAuditLink()).toBeNull();
    expect(component.mutatingId()).toBeNull();
    expect(toast.success).toHaveBeenCalledOnce();
    const refreshedJournal = http.expectOne(candidate => candidate.url === journalUrl);
    expect(refreshedJournal.request.method).toBe('GET');
    refreshedJournal.flush(paymentPage([{ ...contractorLink, status: 'CONFIRMED' }, oldBankLink]));
    flushReadRequests();
    expect(component.links()[0].id).toBe(8327);
  });

  it('preserves entered evidence after a conflict so a retry is deliberate', () => {
    openContractorAudit();
    component.contractorAuditAmount.set('1400');
    component.contractorAuditEffectiveAt.set(statementTime);
    component.contractorAuditReason.set('Выписка получателя 12 октября');
    component.contractorAuditStatementChecked.set(true);
    component.submitContractorSourceAudit();
    http.expectOne(confirmationUrl).flush({ message: 'Источник платежа изменился' }, {
      status: 409,
      statusText: 'Conflict'
    });

    expect(component.contractorAuditLink()?.id).toBe(8327);
    expect(component.contractorAuditAmount()).toBe('1400');
    expect(component.contractorAuditEffectiveAt()).toBe(statementTime);
    expect(component.contractorAuditReason()).toBe('Выписка получателя 12 октября');
    expect(component.contractorAuditStatementChecked()).toBe(true);
    expect(component.contractorAuditError()).toBeTruthy();
    expect(component.mutatingId()).toBeNull();
    expect(toast.success).not.toHaveBeenCalled();
    http.expectNone(candidate => candidate.method === 'POST');

    component.submitContractorSourceAudit();
    const retry = http.expectOne(confirmationUrl);
    expect(retry.request.body.confirmedTotalKopecks).toBe(140_000);
    retry.flush({ ...contractorLink, status: 'CONFIRMED', confirmedAmountKopecks: 140_000 });
    flushReadRequests();
    expect(component.contractorAuditLink()).toBeNull();
  });

  it('keeps the form and server validation error when the recipient time is ahead of server time', () => {
    openContractorAudit();
    component.contractorAuditAmount.set('1400');
    component.contractorAuditEffectiveAt.set('9999-12-31T23:59');
    component.contractorAuditReason.set('Выписка получателя');
    component.contractorAuditStatementChecked.set(true);
    expect(component.canSubmitContractorAudit()).toBe(true);
    component.submitContractorSourceAudit();

    const write = http.expectOne(confirmationUrl);
    expect(write.request.body.effectiveAt).toBe('9999-12-31T23:59');
    write.flush({ message: 'Дата поступления не может быть в будущем' }, {
      status: 400,
      statusText: 'Bad Request'
    });
    expect(component.contractorAuditLink()?.id).toBe(8327);
    expect(component.contractorAuditEffectiveAt()).toBe('9999-12-31T23:59');
    expect(component.contractorAuditError()).toContain('Дата поступления не может быть в будущем');
    expect(component.mutatingId()).toBeNull();
    expect(toast.success).not.toHaveBeenCalled();
  });
});

@Component({ selector: 'app-admin-layout', template: '<ng-content />' })
class LayoutStub {
  title = input('');
  active = input('');
  rightPanelMode = input('default');
}

@Component({ selector: 'app-mobile-bottom-pager', template: '' })
class PagerStub {
  pageIndex = input(0);
  totalPages = input(0);
  disabled = input(false);
  fallbackUrl = input('');
  previous = output<void>();
  next = output<void>();
}

describe('contractor audit dialog', () => {
  it('shows the exact source and traps keyboard focus until the dialog closes', async () => {
    const toast = { error: vi.fn(), success: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toast }
      ]
    });
    TestBed.overrideComponent(TbankPaymentsComponent, {
      remove: { imports: [AdminLayoutComponent, MobileBottomPagerComponent] },
      add: { imports: [LayoutStub, PagerStub] }
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(TbankPaymentsComponent);
    for (const request of http.match(candidate => candidate.method === 'GET')) {
      const url = request.request.url;
      request.flush(url === journalUrl ? {
        ...fixtures.responses.AdminPaymentLinksPageResponseOutput,
        items: [contractorLink, oldBankLink],
        page: 0, size: 25, totalElements: 2, totalPages: 1
      } : url.endsWith('bank-profiles') ? { profiles: [], managers: [] }
        : url.endsWith('manual-tasks') ? []
        : url.includes('monthly-summary') ? { items: [] }
        : {});
    }
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[role="dialog"]')).toBeNull();

    const trigger = host.querySelector<HTMLButtonElement>('button[title^="Сверить выписку"]')!;
    trigger.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const dialog = host.querySelector<HTMLElement>('[role="dialog"][aria-labelledby="contractor-audit-title"]');
    expect(dialog).not.toBeNull();
    expect(dialog!.textContent).toContain('№8327');
    expect(dialog!.textContent).toContain('№25322');
    expect(dialog!.textContent).toContain('Виктория Ц.');
    expect(dialog!.textContent).not.toContain('№8288');
    const amount = dialog!.querySelector<HTMLInputElement>('input[name="contractorAuditAmount"]')!;
    expect(amount.value).toBe('');
    expect(dialog!.querySelector<HTMLInputElement>('input[name="contractorAuditEffectiveAt"]')?.value).toBe('');
    expect(document.activeElement).toBe(amount);
    expect(dialog!.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
    http.expectNone(candidate => candidate.method === 'POST');

    const close = dialog!.querySelector<HTMLButtonElement>('button[aria-label="Закрыть сверку"]')!;
    const cancel = dialog!.querySelector<HTMLButtonElement>('.contractor-audit-cancel')!;
    close.focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(cancel);
    cancel.focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(close);

    const component = fixture.componentInstance;
    component.contractorAuditAmount.set('1400');
    component.contractorAuditEffectiveAt.set(statementTime);
    component.contractorAuditReason.set('Выписка Виктории за 12 октября');
    component.contractorAuditStatementChecked.set(true);
    fixture.detectChanges();
    const submit = dialog!.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(submit.disabled).toBe(false);
    close.focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(submit);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(close);
    http.expectNone(candidate => candidate.method === 'POST');

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);

    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });
});
