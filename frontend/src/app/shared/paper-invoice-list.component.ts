import { Component, Input, OnChanges, OnDestroy, Output, EventEmitter, ChangeDetectorRef, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { openPaperInvoiceDialog, type PaperDocument, type PaperTarget } from './paper-invoice-dialog.component';
@Component({
  selector:'app-paper-invoice-list',
  template:`<section class="paper-list" aria-label="Текущие счета">
    <header><strong>Текущие счета и акты</strong><button type="button" (click)="load()">Обновить</button></header>
    <button type="button" (click)="issue()">Выставить бумажный счёт</button>
    @if(error){<p role="alert">{{error}}</p>}
    @if(!documents.length && !error){<p>Банковские комплекты пока не создавались.</p>}
    @for(d of documents;track d.id){<article>
      <strong>Счёт № {{d.number}}</strong><span>{{d.payer?.name}} · ИНН {{d.payer?.inn}}</span>
      <span>{{(d.amountKopecks/100).toLocaleString('ru-RU')}} ₽ · {{status(d.stage)}}</span>
      @if(d.orderIds?.length){<small>Заказы: {{d.orderIds?.join(', ')}}</small>}
      @if(d.error){<p>{{d.error}}</p>}
      <button type="button" (click)="open(d)">Документы и действия</button>
    </article>}
  </section>`,
  styles:[`.paper-list{border:1px solid #8894a466;border-radius:14px;padding:14px;margin:14px 0}header{display:flex;justify-content:space-between;gap:8px}article{display:grid;gap:8px;padding:12px 0;border-top:1px solid #8894a433;margin-top:12px;overflow-wrap:anywhere}button{border:1px solid #8894a4;border-radius:8px;padding:9px;background:transparent;color:inherit;margin:5px 0;cursor:pointer}p,small{font-size:13px}:host-context(body.otziv-dark-theme) .paper-list button{background:#34414c;color:#f0f4f8;border-color:#81909e}`]
})
export class PaperInvoiceListComponent implements OnChanges,OnDestroy {
  @Input({required:true}) invoiceId!:number;
  @Output() readonly updated=new EventEmitter<void>();
  private readonly http=inject(HttpClient);private readonly view=inject(ChangeDetectorRef);documents:PaperDocument[]=[];error='';private ticket=0;private loaded=false;
  private timer:ReturnType<typeof setInterval>|null=null;
  ngOnChanges():void{if(this.timer)clearInterval(this.timer);this.documents=[];this.loaded=false;this.load();this.timer=setInterval(()=>this.load(),10000);}
  load():void{if(!this.invoiceId)return;const ticket=++this.ticket;this.http.get<PaperDocument[]>(`/api/paper-invoices/COMMON_INVOICE/${this.invoiceId}/documents`).subscribe({next:d=>{if(ticket===this.ticket){const signature=(rows:PaperDocument[])=>rows.map(v=>`${v.id}:${v.stage}:${v.paidAt}`).join('|');const changed=this.loaded && signature(d)!==signature(this.documents);this.documents=d;this.loaded=true;this.error='';this.view.markForCheck();if(changed)this.updated.emit();}},error:e=>{if(ticket===this.ticket){this.error=e?.error?.detail || 'Не удалось загрузить банковские документы';this.view.markForCheck();}}});}
  issue():void{openPaperInvoiceDialog({kind:'COMMON_INVOICE',id:this.invoiceId},()=>{},()=>{this.load();this.updated.emit();});}
  open(d:PaperDocument):void{openPaperInvoiceDialog({kind:d.targetKind || 'COMMON_INVOICE',id:d.targetId || this.invoiceId} as PaperTarget,()=>{},()=>{this.load();this.updated.emit();});}
  status(stage:string):string{return ({PENDING:'Ожидает формирования',INVOICE_CREATING:'Создаётся счёт',INVOICE_CREATED:'Создаётся акт',ACT_CREATING:'Создаётся акт',ACT_CREATED:'Получаем PDF',FILES_READY:'Отправка документов',WAITING_PAYMENT:'Ожидает оплаты',PAID:'Оплачен',CANCEL_REQUESTED:'Отмена в процессе',CANCELLED:'Отменён',CANCEL_REVIEW:'Отмена требует сверки'} as Record<string,string>)[stage] || 'Требуется сверка';}
  ngOnDestroy():void{++this.ticket;if(this.timer)clearInterval(this.timer);}
}
