import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CommonInvoiceOverdueTermComponent, type CommonInvoiceOverdueTerm } from './common-invoice-overdue-term.component';

describe('Common invoice overdue term', () => {
  let http: HttpTestingController;
  const term = (patch: Partial<CommonInvoiceOverdueTerm> = {}): CommonInvoiceOverdueTerm => ({
    invoiceId: 4, overdueDays: 30, extensionDays: 0, extensionBy: null, extensionAt: null,
    termStart: '2026-11-07T19:22:12', paperDelivery: true, automaticMoveNotBefore: '2026-12-07T19:22:12',
    editable: true, note: 'Срок 30 дн. от доставки бумажного счёта 07.11.2026.', ...patch
  });

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CommonInvoiceOverdueTermComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  function render(initial: CommonInvoiceOverdueTerm) {
    const fixture = TestBed.createComponent(CommonInvoiceOverdueTermComponent);
    fixture.componentInstance.invoiceId = 4;
    fixture.componentInstance.ngOnChanges();
    http.expectOne('/api/common-billing/invoices/4/overdue-term').flush(initial);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the move date and the term explanation', () => {
    const fixture = render(term());
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Переход в «Не оплачено»');
    expect(text).toContain('не раньше 07.12.2026');
    expect(text).toContain('от доставки бумажного счёта');
  });

  it('saves a week of extension and shows who granted it', () => {
    const fixture = render(term());
    const c = fixture.componentInstance;
    c.days = 7; c.save();
    const put = http.expectOne({ method: 'PUT', url: '/api/common-billing/invoices/4/overdue-term' });
    expect(put.request.body).toEqual({ extensionDays: 7 });
    put.flush(term({ extensionDays: 7, extensionBy: 'lika', extensionAt: '2026-11-07T20:00:00', automaticMoveNotBefore: '2026-12-14T19:22:12' }));
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('не раньше 14.12.2026');
    expect(text).toContain('Отсрочка 7 дн. задана: lika');
  });

  it('rejects an invalid number before sending and shows a refusal', () => {
    const fixture = render(term());
    const c = fixture.componentInstance;
    c.days = 61; c.save();
    c.days = 2.5; c.save();
    http.expectNone('/api/common-billing/invoices/4/overdue-term');
    c.days = 3; c.save();
    http.expectOne({ method: 'PUT', url: '/api/common-billing/invoices/4/overdue-term' })
      .flush({ detail: 'Отсрочку можно задать только для неоплаченного открытого общего счёта' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(c.error()).toContain('только для неоплаченного');
  });

  it('a closed invoice shows the explanation without the form', () => {
    const fixture = render(term({ editable: false, automaticMoveNotBefore: null, note: 'Счёт закрыт или уже переведён; срок не применяется.' }));
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('срок не применяется');
  });
});
