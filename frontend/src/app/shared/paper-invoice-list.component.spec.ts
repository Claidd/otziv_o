import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PaperInvoiceListComponent } from './paper-invoice-list.component';
describe('Live paper invoice list',()=>{
  it('refreshes the parent when a document is paid and ignores stale list replies',()=>{
    TestBed.configureTestingModule({imports:[PaperInvoiceListComponent],providers:[provideHttpClient(),provideHttpClientTesting()]});
    const f=TestBed.createComponent(PaperInvoiceListComponent),h=TestBed.inject(HttpTestingController);f.componentRef.setInput('invoiceId',8);f.detectChanges();
    const initial={id:'first',number:'PB-first',amountKopecks:10000,stage:'WAITING_PAYMENT',paidAt:null};
    h.expectOne('/api/paper-invoices/COMMON_INVOICE/8/context').flush({enabled:true,documents:[initial]});let updated=0;f.componentInstance.updated.subscribe(()=>updated++);
    const states: unknown[]=[];f.componentInstance.stateChanged.subscribe(state=>states.push(state));
    f.componentInstance.load();const stale=h.expectOne('/api/paper-invoices/COMMON_INVOICE/8/context');f.componentInstance.load();
    h.expectOne('/api/paper-invoices/COMMON_INVOICE/8/context').flush({enabled:true,documents:[{...initial,stage:'PAID',paidAt:'2026-11-01'}]});stale.flush({enabled:true,documents:[initial]});f.detectChanges();
    expect(states).toHaveLength(1);
    expect(states[0]).toMatchObject({invoiceId:8,context:{documents:[{stage:'PAID'}]}});
    expect(updated).toBe(1);expect(f.nativeElement.textContent).toContain('Оплачен');f.destroy();h.verify();
  });
});
