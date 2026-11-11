import { Component, Input, OnChanges, OnDestroy, Output, EventEmitter, ChangeDetectorRef, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { isSettlingPaperDocument, openPaperInvoiceDialog, type Context, type PaperDocument, type PaperTarget } from './paper-invoice-dialog.component';
import { VisibilityAwarePoller, backoffDelayMs } from './visibility-aware-poller';
import type { PaperInvoiceListState } from './paper-invoice-workflow';
@Component({
  selector:'app-paper-invoice-list',
  template:`<section class="paper-list" aria-label="Выпущенные документы Точки">
    <header>
      <strong>Выпущенные документы</strong>
      <button type="button" class="paper-list-button paper-list-refresh" (click)="load()" title="Список обновляется сам; нажмите, чтобы проверить сразу">
        <span class="material-icons-sharp" aria-hidden="true">refresh</span>Обновить
      </button>
    </header>
    @if(issueAction){<button type="button" class="paper-list-button" (click)="issue()">Выставить бумажный счёт</button>}
    @if(error){<p role="alert" class="paper-list-error">{{error}}</p>}
    @if(!documents.length && !error){<p>Счёт и акт ещё не выпускались.</p>}
    @if(ordinaryOrders.length){<article class="ordinary"><strong>Обычные реквизиты</strong><span>Заказы № {{ordinaryOrders.join(', ')}}</span><small>Оплаты учитываются в общей сумме. Реквизиты и напоминания доступны в карточках этих заказов.</small></article>}
    @for(d of documents;track d.id){<article>
      @if(d.orderIds?.length){<strong>Заказы № {{d.orderIds?.join(', ')}}</strong>}
      <span class="reference">Счёт № {{d.number}}</span><span>{{d.payer?.name}} · ИНН {{d.payer?.inn}}</span>
      <span class="amount">{{(d.amountKopecks/100).toLocaleString('ru-RU')}} ₽ · {{d.paidAt ? 'Оплачен' : status(d.stage)}}</span>
      @if(d.error){<p class="paper-list-error">{{d.error}}</p>}
      <button type="button" class="paper-list-button" (click)="open(d)">Документы и действия</button>
    </article>}
  </section>`,
  styles:[`
    :host{display:block;min-width:0}
    *{box-sizing:border-box}
    .paper-list{display:grid;gap:.5rem;min-width:0;border-top:1px dashed var(--billing-panel-border,#d8dfe7);padding-top:.6rem;color:var(--billing-action-text,inherit)}
    header{display:flex;align-items:center;justify-content:space-between;gap:.5rem}
    header strong{font-size:.74rem;font-weight:950}
    p{margin:0;font-size:.68rem;font-weight:750;line-height:1.35;color:var(--billing-action-muted,#596675)}
    .paper-list-error{color:var(--billing-alert-title,#9f173c)}
    article{display:grid;gap:.28rem;min-width:0;border:1px solid var(--billing-panel-border,#d8dfe7);border-radius:.6rem;padding:.55rem .6rem;background:var(--billing-panel-surface,#fbfcfd);font-size:.7rem;font-weight:800;line-height:1.35;overflow-wrap:anywhere}
    article strong{font-size:.72rem;font-weight:950}
    .amount{font-weight:950}
    .reference,small{font-size:.66rem;color:var(--billing-action-muted,#596675)}
    .paper-list .paper-list-button{display:inline-flex;align-items:center;justify-content:center;gap:.3rem;min-height:2.1rem;max-width:100%;border:1px solid var(--billing-action-border,#d8dfe7);border-radius:.55rem;padding:.3rem .65rem;background:var(--billing-action-surface,#f5f7fa);color:var(--billing-action-text,inherit);box-shadow:none;font:inherit;font-size:.7rem;font-weight:900;line-height:1.2;white-space:normal;overflow-wrap:anywhere;cursor:pointer}
    .paper-list .paper-list-button:not(:disabled):hover{border-color:var(--billing-action-border,#d8dfe7);color:var(--billing-action-text,inherit);background:var(--billing-action-surface-hover,#e9eef5)}
    .paper-list .paper-list-button:focus-visible{outline:3px solid color-mix(in srgb,var(--otziv-action,#e53220) 30%,transparent);outline-offset:2px}
    .paper-list .paper-list-refresh{min-height:1.75rem;padding:.15rem .5rem;font-size:.64rem}
    .paper-list-refresh .material-icons-sharp{font-size:.9rem}
    article .paper-list-button{width:100%;margin-top:.15rem}
  `]
})
export class PaperInvoiceListComponent implements OnChanges,OnDestroy {
  @Input({required:true}) invoiceId!:number;
  /** False when the host already offers the same «Счёт и акт» dialog next to the list. */
  @Input() issueAction=true;
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
