import { Component, Input, OnChanges, OnDestroy, Output, EventEmitter, ChangeDetectorRef, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { isSettlingPaperDocument, openPaperInvoiceDialog, type Context, type PaperDocument, type PaperTarget } from './paper-invoice-dialog.component';
import { VisibilityAwarePoller, backoffDelayMs } from './visibility-aware-poller';
import type { PaperInvoiceListState } from './paper-invoice-workflow';
@Component({
  selector:'app-paper-invoice-list',
  template:`<section class="paper-list" aria-label="Текущие счета">
    <header><strong>Текущие счета и акты</strong><button type="button" (click)="load()">Обновить</button></header>
    <button type="button" (click)="issue()">Выставить бумажный счёт</button>
    @if(error){<p role="alert">{{error}}</p>}
    @if(!documents.length && !error){<p>Банковские комплекты пока не создавались.</p>}
    @if(ordinaryOrders.length){<article class="ordinary"><strong>Обычные реквизиты</strong><span>Заказы № {{ordinaryOrders.join(', ')}}</span><small>Оплаты учитываются в общей сумме. Реквизиты и напоминания доступны в карточках этих заказов.</small></article>}
    @for(d of documents;track d.id){<article>
      @if(d.orderIds?.length){<strong>Заказы № {{d.orderIds?.join(', ')}}</strong>}
      <span class="reference">Счёт № {{d.number}}</span><span>{{d.payer?.name}} · ИНН {{d.payer?.inn}}</span>
      <span class="amount">{{(d.amountKopecks/100).toLocaleString('ru-RU')}} ₽ · {{d.paidAt ? 'Оплачен' : status(d.stage)}}</span>
      @if(d.error){<p>{{d.error}}</p>}
      <button type="button" (click)="open(d)">Документы и действия</button>
    </article>}
  </section>`,
  styles:[`
    :host{--list-bg:#f8fafc;--list-border:#d8dfe7;--list-muted:#596675;--list-card:#fff;--list-button:#f0f4f8}
    :host-context(body.otziv-dark-theme){--list-bg:#242e36;--list-border:#536473;--list-muted:#bbcad5;--list-card:#293640;--list-button:#344551}
    *{box-sizing:border-box}.paper-list{background:var(--list-bg);border:1px solid var(--list-border);border-radius:18px;padding:20px;margin:16px 0;min-width:0}
    header{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;margin-bottom:12px}header strong{font-size:16px}
    article{display:grid;gap:10px;background:var(--list-card);border:1px solid var(--list-border);border-radius:14px;padding:16px;margin-top:16px;overflow-wrap:anywhere;line-height:1.45}.amount{font-weight:700}.reference,small{font-size:13px;color:var(--list-muted)}
    button{border:1px solid var(--list-border);border-radius:12px;min-height:44px;padding:11px 14px;background:var(--list-button);color:inherit;font:inherit;line-height:1.35;cursor:pointer;max-width:100%;white-space:normal;overflow-wrap:anywhere}article button{width:100%;margin-top:4px}p{font-size:13px;line-height:1.5;color:var(--list-muted)}button:focus-visible{outline:3px solid #dc8e67;outline-offset:2px}
    @media(max-width:600px){.paper-list{padding:16px}header button{flex:0 0 auto}}
  `]
})
export class PaperInvoiceListComponent implements OnChanges,OnDestroy {
  @Input({required:true}) invoiceId!:number;
  @Output() readonly updated=new EventEmitter<void>();
  @Output() readonly stateChanged=new EventEmitter<PaperInvoiceListState | null>();
  private readonly http=inject(HttpClient);private readonly view=inject(ChangeDetectorRef);documents:PaperDocument[]=[];ordinaryOrders:number[]=[];error='';private ticket=0;private loaded=false;
  private quietPolls=0;private failedPolls=0;
  // Every 10 s while a bank document is being prepared or sent; otherwise the
  // interval doubles while nothing changes (up to a minute), and nothing is
  // requested while the tab is hidden.
  private readonly poller=new VisibilityAwarePoller(()=>this.load(),()=>this.failedPolls>0
    ? backoffDelayMs(10_000,this.failedPolls,120_000)
    : this.documents.some(isSettlingPaperDocument) ? 10_000 : backoffDelayMs(10_000,this.quietPolls,60_000));
  ngOnChanges():void{this.poller.stop();this.documents=[];this.ordinaryOrders=[];this.loaded=false;this.quietPolls=0;this.failedPolls=0;this.stateChanged.emit(null);this.load();this.poller.start();}
  load():void{
    if(!this.invoiceId)return;
    const ticket=++this.ticket,invoiceId=this.invoiceId;
    this.http.get<Context>(`/api/paper-invoices/COMMON_INVOICE/${invoiceId}/context`).subscribe({
      next:context=>{
        if(ticket!==this.ticket)return;
        const documents=context.documents ?? [];
        const signature=(rows:PaperDocument[])=>rows.map(v=>`${v.id}:${v.stage}:${v.paidAt}:${v.invoiceDelivery}:${v.actDelivery}`).join('|');
        const changed=this.loaded && signature(documents)!==signature(this.documents);
        this.failedPolls=0;this.quietPolls=changed?0:this.quietPolls+1;
        this.documents=documents;this.ordinaryOrders=(context.positions ?? []).filter(p=>p.documentId==='ORDINARY').map(p=>p.orderId);this.loaded=true;this.error='';this.view.markForCheck();
        this.stateChanged.emit({invoiceId,context});
        if(changed)this.updated.emit();
        this.poller.reschedule();
      },
      error:e=>{if(ticket===this.ticket){this.error=e?.error?.detail || 'Не удалось загрузить банковские документы';this.failedPolls+=1;this.poller.reschedule();this.stateChanged.emit(null);this.view.markForCheck();}}
    });
  }
  issue():void{openPaperInvoiceDialog({kind:'COMMON_INVOICE',id:this.invoiceId},()=>{},()=>{this.load();this.updated.emit();});}
  open(d:PaperDocument):void{openPaperInvoiceDialog({kind:d.targetKind || 'COMMON_INVOICE',id:d.targetId || this.invoiceId} as PaperTarget,()=>{},()=>{this.load();this.updated.emit();});}
  status(stage:string):string{return ({PENDING:'Ожидает формирования',INVOICE_CREATING:'Создаётся счёт',INVOICE_CREATED:'Создаётся акт',ACT_CREATING:'Создаётся акт',ACT_CREATED:'Получаем PDF',FILES_READY:'Отправка документов',WAITING_PAYMENT:'Ожидает оплаты',PAID:'Оплачен',CANCEL_REQUESTED:'Отмена в процессе',CANCELLED:'Отменён',CANCEL_REVIEW:'Отмена требует сверки'} as Record<string,string>)[stage] || 'Требуется сверка';}
  ngOnDestroy():void{++this.ticket;this.poller.stop();}
}
