import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PaperInvoiceDialogComponent, openPaperInvoiceDialog } from './paper-invoice-dialog.component';

describe('Paper invoice asynchronous lifecycle', () => {
  let http: HttpTestingController;
  let fixture: ReturnType<typeof TestBed.createComponent<PaperInvoiceDialogComponent>>;
  const payer = { type: 'company', name: 'Тест', inn: '7707083893', kpp: '773601001', address: '' };
  const doc = (id: string, stage = 'PENDING') => ({ id, number: `PB-${id}`, amountKopecks: 10000, stage, invoiceDelivery: 'PENDING', actDelivery: 'PENDING', error: null, paidAt: stage === 'PAID' ? '2026-10-31T12:00:00' : null });
  function open(id: number, document: ReturnType<typeof doc> | null = null): void {
    openPaperInvoiceDialog({kind: 'ORDER', id}); fixture.detectChanges();
    http.expectOne(`/api/paper-invoices/ORDER/${id}/context`).flush({enabled:true,configured:true,lookupAvailable:true,companyId:id,companyTitle:`Company ${id}`,amountKopecks:10000,accountLast4:'6542',payer,document});
    fixture.detectChanges();
  }
  beforeEach(() => {
    TestBed.configureTestingModule({imports:[PaperInvoiceDialogComponent],providers:[provideHttpClient(),provideHttpClientTesting()]});
    fixture=TestBed.createComponent(PaperInvoiceDialogComponent); http=TestBed.inject(HttpTestingController);fixture.detectChanges();
  });
  afterEach(() => { fixture.componentInstance.close();fixture.destroy();http.verify(); });

  it('closing the unissued form performs no bank mutation', () => {
    open(1); fixture.componentInstance.close();expect(fixture.componentInstance.opened()).toBe(false);
    http.expectNone(r=>r.method==='POST');
  });
  it('an older INN lookup cannot overwrite a newer result for the same INN', () => {
    open(1);const c=fixture.componentInstance;c.lookup();const old=http.expectOne(r=>r.url.endsWith('/lookup'));
    c.lookup();const current=http.expectOne(r=>r.url.endsWith('/lookup'));
    current.flush({candidates:[{...payer,name:'Current'}]});old.flush({candidates:[{...payer,name:'Stale'}]});
    expect(c.draft.name).toBe('Current');expect(c.busy()).toBe(false);
  });
  it('late issue completion cannot put the previous company document into a new form', () => {
    open(1);const c=fixture.componentInstance;c.confirmed=true;c.issue();const old=http.expectOne('/api/paper-invoices/ORDER/1/issue');
    c.close();expect(c.opened()).toBe(true); // closing an in-flight issue is not an invoice cancellation
    open(2);old.flush(doc('old'));expect(c.context()?.companyId).toBe(2);expect(c.context()?.document).toBeNull();
  });
  it('late save failure cannot clear a new issue lock or show an error for another company', () => {
    open(1);const c=fixture.componentInstance;c.save();const old=http.expectOne('/api/paper-invoices/ORDER/1/payer');
    open(2);c.confirmed=true;c.issue();const current=http.expectOne('/api/paper-invoices/ORDER/2/issue');
    old.flush({detail:'Old error'},{status:409,statusText:'Conflict'});
    expect(c.busy()).toBe(true);expect(c.error()).toBe('');current.flush(doc('new'));expect(c.context()?.document?.id).toBe('new');
  });
  it('a stale waiting response cannot undo a confirmed paid state in the dialog', () => {
    open(1,doc('1','WAITING_PAYMENT'));const c=fixture.componentInstance;
    c.poll();const old=http.expectOne('/api/paper-invoices/documents/1');c.poll();const current=http.expectOne('/api/paper-invoices/documents/1');
    current.flush(doc('1','PAID'));old.flush(doc('1','WAITING_PAYMENT'));expect(c.context()?.document?.stage).toBe('PAID');
  });
  it('an issue request freezes the payer snapshot and cannot be submitted twice', () => {
    open(1);const c=fixture.componentInstance;c.confirmed=true;c.issue();c.issue();c.innChanged('380124742639');
    const issue=http.expectOne('/api/paper-invoices/ORDER/1/issue');expect(issue.request.body.payer.inn).toBe(payer.inn);
    expect(c.draft.inn).toBe(payer.inn);issue.flush(doc('1'));
  });
});
