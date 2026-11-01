import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PaperInvoiceListComponent } from './paper-invoice-list.component';
describe('Live paper invoice list',()=>{
  it('refreshes the parent when a document is paid and ignores stale list replies',()=>{
    TestBed.configureTestingModule({imports:[PaperInvoiceListComponent],providers:[provideHttpClient(),provideHttpClientTesting()]});
    const f=TestBed.createComponent(PaperInvoiceListComponent),h=TestBed.inject(HttpTestingController);f.componentRef.setInput('invoiceId',8);f.detectChanges();
    const initial={id:'first',number:'PB-first',amountKopecks:10000,stage:'WAITING_PAYMENT',paidAt:null};
    h.expectOne('/api/paper-invoices/COMMON_INVOICE/8/documents').flush([initial]);let updated=0;f.componentInstance.updated.subscribe(()=>updated++);
    f.componentInstance.load();const stale=h.expectOne('/api/paper-invoices/COMMON_INVOICE/8/documents');f.componentInstance.load();
    h.expectOne('/api/paper-invoices/COMMON_INVOICE/8/documents').flush([{...initial,stage:'PAID',paidAt:'2026-11-01'}]);stale.flush([initial]);f.detectChanges();
    expect(updated).toBe(1);expect(f.nativeElement.textContent).toContain('Оплачен');f.destroy();h.verify();
  });
});
