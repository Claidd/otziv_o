import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PaperInvoiceDialogComponent, openPaperInvoiceDialog } from './paper-invoice-dialog.component';

describe('Paper invoice asynchronous lifecycle', () => {
  let http: HttpTestingController;
  let fixture: ReturnType<typeof TestBed.createComponent<PaperInvoiceDialogComponent>>;
  const payer = { type: 'company', name: 'Тест', inn: '7707083893', kpp: '773601001', address: '' };
  const doc = (id: string, stage = 'PENDING') => ({ id, number: `PB-${id}`, amountKopecks: 10000, stage, invoiceDelivery: 'PENDING', actDelivery: 'PENDING', error: null, paidAt: stage === 'PAID' ? '2026-10-31T12:00:00' : null, cancellationDelivery: stage==='CANCELLED'?'SKIPPED':'PENDING', replacementState: undefined as string | undefined });
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

  it('common selection totals only selected eligible orders and freezes the saved payer version',()=>{
    openPaperInvoiceDialog({kind:'COMMON_INVOICE',id:8});fixture.detectChanges();
    http.expectOne('/api/paper-invoices/COMMON_INVOICE/8/context').flush({enabled:true,configured:true,payer:null,document:null,payers:[{id:'choice',payer,primary:true,version:7}],positions:[
      {orderId:1,companyId:1,companyTitle:'A',amountKopecks:10000,ready:true,paid:false,documentId:null},
      {orderId:2,companyId:1,companyTitle:'A',amountKopecks:20000,ready:true,paid:false,documentId:null},
      {orderId:3,companyId:2,companyTitle:'B',amountKopecks:30000,ready:false,paid:false,documentId:null},
      {orderId:4,companyId:2,companyTitle:'B',amountKopecks:40000,ready:true,paid:false,documentId:'existing'}]});fixture.detectChanges();
    const c=fixture.componentInstance;c.toggleOrder(1);c.toggleOrder(2);c.toggleOrder(3);c.toggleOrder(4);expect(c.selectedAmount()).toBe(30000);
    c.chooseSaved('choice');c.confirmed=true;c.issue();const issue=http.expectOne('/api/paper-invoices/COMMON_INVOICE/8/issue');
    expect(issue.request.body.orderIds).toEqual([1,2]);expect(issue.request.body.payerChoiceVersion).toBe(7);expect(issue.request.body.payerChoiceId).toBe('choice');issue.flush({...doc('part'),targetKind:'COMMON_PART',targetId:9});
  });
  it('an order already inside a common invoice opens its subset selector',()=>{
    openPaperInvoiceDialog({kind:'ORDER',id:5});fixture.detectChanges();
    http.expectOne('/api/paper-invoices/ORDER/5/context').flush({enabled:true,parentInvoiceId:8});fixture.detectChanges();
    http.expectOne('/api/paper-invoices/COMMON_INVOICE/8/context').flush({enabled:true,configured:true,payer:null,document:null,positions:[{orderId:5,ready:true,paid:false,documentId:null,amountKopecks:12300}]});fixture.detectChanges();
    expect(fixture.componentInstance.selectedOrders()).toEqual([5]);expect(fixture.componentInstance.selectedAmount()).toBe(12300);
  });
  it('cancelling one common part requests ordinary payment only for its own target',()=>{
    openPaperInvoiceDialog({kind:'COMMON_PART',id:9});fixture.detectChanges();
    http.expectOne('/api/paper-invoices/COMMON_PART/9/context').flush({enabled:true,document:{...doc('part','WAITING_PAYMENT'),targetKind:'COMMON_PART'},parentInvoiceId:8});fixture.detectChanges();
    const c=fixture.componentInstance;c.cancelReason='Новые реквизиты';c.cancelConfirmed=true;c.cancel();
    const cancel=http.expectOne('/api/paper-invoices/documents/part/cancel');expect(cancel.request.body.replaceWithOrdinary).toBe(true);cancel.flush(doc('part','CANCEL_REQUESTED'));
  });

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
  it('cancellation requires a reason and confirmation and rejects duplicate submissions', () => {
    open(1,doc('1','WAITING_PAYMENT'));const c=fixture.componentInstance;
    c.cancel();http.expectNone(r=>r.url.endsWith('/cancel'));
    c.cancelReason='Ошибка реквизитов';c.cancelConfirmed=true;c.cancel();c.cancel();
    const cancel=http.expectOne('/api/paper-invoices/documents/1/cancel');
    expect(cancel.request.body).toEqual({reason:'Ошибка реквизитов',confirmed:true,replaceWithOrdinary:true});
    cancel.flush(doc('1','CANCEL_REQUESTED'));expect(c.context()?.document?.stage).toBe('CANCEL_REQUESTED');expect(c.canCancel()).toBe(false);
  });
  it('an old waiting poll cannot undo the cancellation response', () => {
    open(1,doc('1','WAITING_PAYMENT'));const c=fixture.componentInstance;c.poll();const stale=http.expectOne('/api/paper-invoices/documents/1');
    c.cancelReason='Ошибка';c.cancelConfirmed=true;c.cancel();http.expectOne('/api/paper-invoices/documents/1/cancel').flush(doc('1','CANCEL_REQUESTED'));
    stale.flush(doc('1','WAITING_PAYMENT'));expect(c.context()?.document?.stage).toBe('CANCEL_REQUESTED');
  });
  it('paid documents cannot be cancelled and a replacement requires completed cancellation', () => {
    open(1,doc('1','PAID'));const c=fixture.componentInstance;c.cancelReason='Ошибка';c.cancelConfirmed=true;c.cancel();c.newInvoice();
    http.expectNone(r=>r.method==='POST');expect(c.replacement()).toBe(false);
    open(2,doc('2','CANCELLED'));c.newInvoice();expect(c.replacement()).toBe(true);expect(c.confirmed).toBe(false);
    fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Сумма: 100 ₽');
    c.confirmed=true;c.issue();http.expectOne('/api/paper-invoices/ORDER/2/issue').flush(doc('new'));
    expect(c.replacement()).toBe(false);expect(c.context()?.document?.id).toBe('new');
  });
  it('a replacement waits for the cancellation notice receipt', () => {
    open(1,{...doc('1','CANCELLED'),cancellationDelivery:'UNKNOWN'});const c=fixture.componentInstance;
    c.newInvoice();expect(c.replacement()).toBe(false);expect(c.canReplace()).toBe(false);
    open(2,{...doc('2','CANCELLED'),cancellationDelivery:'SENT'});c.newInvoice();expect(c.replacement()).toBe(true);
  });
  it('ordinary handoff has one action and cannot reissue a paper invoice', () => {
    open(1,doc('1','WAITING_PAYMENT'));fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Сменить способ оплаты');
    open(2,{...doc('2','CANCELLED'),replacementState:'PENDING'});const c=fixture.componentInstance;fixture.detectChanges();
    c.newInvoice();expect(c.replacement()).toBe(false);expect(c.canReplace()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('по текущим резервам');
    open(3,{...doc('3','CANCELLED'),replacementState:'QUEUED'});fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Обычный счёт передан автоответчику');
    expect(fixture.nativeElement.textContent).not.toContain('Выставить новый счёт');
  });
  it('a successful poll clears a transient restart error without overriding a mutation failure', () => {
    open(1,doc('1','CANCEL_REQUESTED'));const c=fixture.componentInstance;
    c.poll();http.expectOne('/api/paper-invoices/documents/1').flush({}, {status:503,statusText:'Restarting'});
    expect(c.error()).not.toBe('');c.poll();
    http.expectOne('/api/paper-invoices/documents/1').flush({...doc('1','CANCELLED'),replacementState:'QUEUED'});
    expect(c.error()).toBe('');
    open(2,doc('2','WAITING_PAYMENT'));c.poll();
    http.expectOne('/api/paper-invoices/documents/2').flush({}, {status:503,statusText:'Restarting'});
    c.cancelReason='Ошибка';c.cancelConfirmed=true;c.cancel();
    http.expectOne('/api/paper-invoices/documents/2/cancel').flush({detail:'Уже оплачено'}, {status:409,statusText:'Conflict'});
    c.poll();http.expectOne('/api/paper-invoices/documents/2').flush(doc('2','WAITING_PAYMENT'));
    expect(c.error()).toBe('Уже оплачено');
  });
  it('an unconfirmed automatic send can be closed by confirming the manual delivery',()=>{
    const stuck={...doc('7','FILES_READY'),messageDelivery:'SENT',invoiceDelivery:'UNKNOWN',actDelivery:'UNKNOWN',deliveryCompleted:false};
    open(7,stuck);const c=fixture.componentInstance;
    expect(c.canConfirmManualDelivery()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Документы отправлены вручную');
    c.confirmManualDelivery();http.expectNone('/api/paper-invoices/documents/7/manual-delivery');
    c.manualDeliveryConfirmed=true;c.confirmManualDelivery();
    http.expectOne({method:'POST',url:'/api/paper-invoices/documents/7/manual-delivery'})
      .flush({...stuck,stage:'WAITING_PAYMENT',invoiceDelivery:'SENT',actDelivery:'SENT',deliveryCompleted:true});
    fixture.detectChanges();
    expect(c.context()?.document?.stage).toBe('WAITING_PAYMENT');expect(c.canConfirmManualDelivery()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Документы отправлены вручную');
  });
  it('a routine send in progress offers no manual confirmation',()=>{
    open(8,{...doc('8','FILES_READY'),messageDelivery:'SENT',invoiceDelivery:'SENDING',actDelivery:'UNKNOWN'} as any);
    expect(fixture.componentInstance.canConfirmManualDelivery()).toBe(false);
    fixture.componentInstance.close();
    open(9,{...doc('9','FILES_READY'),messageDelivery:'PENDING'} as any);
    expect(fixture.componentInstance.canConfirmManualDelivery()).toBe(false);
  });
});
