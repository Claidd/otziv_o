import { describe, expect, it } from 'vitest';
import type { Context, PaperDocument, Position } from './paper-invoice-dialog.component';
import { paperInvoiceWorkflow } from './paper-invoice-workflow';

const invoice = {id:322,sentAt:'2026-11-01T16:32:27',paperInvoiceIssuedAt:null};
const positions = [1,2,3].map(orderId=>({orderId,paid:false,documentId:null} as Position));
const doc = (stage:string, orderIds=[1,2,3]):PaperDocument => ({
  id:'doc',number:'PB-test',stage,amountKopecks:10000,paidAt:null,error:null,
  invoiceDelivery:'PENDING',actDelivery:'PENDING',orderIds,targetKind:'COMMON_PART'
});
const state = (documents:PaperDocument[], extra:Partial<Context>={}) =>
  ({invoiceId:322,context:{enabled:true,documents,positions,...extra} as Context});

describe('common paper invoice workflow',()=>{
  it('does not treat the readiness notice as document creation or delivery',()=>{
    const result=paperInvoiceWorkflow(invoice,state([]));
    expect(result.prepared).toBe(false);
    expect(result.delivered).toBe(false);
    expect(result.manualAllowed).toBe(false);
    expect(result.preparationText).toContain('ещё не созданы');
  });
  it('requires both PDFs to be ready and both deliveries confirmed',()=>{
    expect(paperInvoiceWorkflow(invoice,state([doc('ACT_CREATED')])).prepared).toBe(false);
    const ready=doc('FILES_READY');
    expect(paperInvoiceWorkflow(invoice,state([ready])).prepared).toBe(true);
    expect(paperInvoiceWorkflow(invoice,state([ready])).delivered).toBe(false);
    expect(paperInvoiceWorkflow(invoice,state([{...ready,invoiceDelivery:'SENT',actDelivery:'UNKNOWN'}])).delivered).toBe(false);
    expect(paperInvoiceWorkflow(invoice,state([{...ready,invoiceDelivery:'SENT',actDelivery:'SENT'}])).delivered).toBe(true);
  });
  it('does not mark all orders delivered after the first subset, even with the legacy timestamp',()=>{
    const first={...doc('WAITING_PAYMENT',[1,2]),invoiceDelivery:'SENT',actDelivery:'SENT'};
    const result=paperInvoiceWorkflow({...invoice,paperInvoiceIssuedAt:'2026-11-02'},state([first]));
    expect(result.prepared).toBe(false);
    expect(result.delivered).toBe(false);
    expect(result.anyDelivered).toBe(true);
    expect(result.preparationText).toContain('2 из 3');
  });
  it('accounts for paid positions and rejects cancelled or cancelling documents',()=>{
    const paidPositions=positions.map(p=>({...p,paid:p.orderId===3}));
    expect(paperInvoiceWorkflow(invoice,state([doc('FILES_READY',[1,2])],{positions:paidPositions})).prepared).toBe(true);
    for(const stage of ['CANCEL_REQUESTED','CANCEL_REVIEW','CANCELLED']) {
      expect(paperInvoiceWorkflow(invoice,state([doc(stage)])).prepared).toBe(false);
    }
  });
  it('ignores another invoice, failed loading and prevents manual confirmation of bank documents',()=>{
    expect(paperInvoiceWorkflow({id:323},state([doc('FILES_READY')])).prepared).toBe(false);
    expect(paperInvoiceWorkflow(invoice,null).manualAllowed).toBe(false);
    expect(paperInvoiceWorkflow(invoice,state([doc('FILES_READY')],{enabled:false})).manualAllowed).toBe(false);
    expect(paperInvoiceWorkflow(invoice,state([],{enabled:false})).manualAllowed).toBe(true);
  });
  it('recognizes a legacy whole bank document without per-order bindings',()=>{
    const full={...doc('WAITING_PAYMENT',[]),targetKind:'COMMON_INVOICE',targetId:322,invoiceDelivery:'SENT',actDelivery:'SENT'};
    expect(paperInvoiceWorkflow(invoice,state([full])).delivered).toBe(true);
  });
  it('keeps ordinary positions in the overall invoice without asking to issue PDFs for them',()=>{
    const mixed=positions.map(p=>({...p,documentId:p.orderId===3?'ORDINARY':null}));
    const progress=paperInvoiceWorkflow(invoice,state([doc('FILES_READY',[1,2])],{positions:mixed}));
    expect(progress.prepared).toBe(true);
    expect(progress.ordinaryCount).toBe(1);
    expect(progress.ordinaryOnly).toBe(false);
    const allOrdinary=positions.map(p=>({...p,documentId:'ORDINARY'}));
    expect(paperInvoiceWorkflow(invoice,state([],{positions:allOrdinary})).ordinaryOnly).toBe(true);
  });
});
