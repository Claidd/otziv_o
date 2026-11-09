import { Component, effect, inject, OnDestroy, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { VisibilityAwarePoller, backoffDelayMs } from './visibility-aware-poller';

export interface PaperTarget { kind: 'ORDER' | 'COMMON_INVOICE' | 'COMMON_PART'; id: number; }
interface Payer { type: string; name: string; inn: string; kpp: string; address: string; }
export interface PaperDocument { id: string; number: string; amountKopecks: number; receivedKopecks?: number; stage: string; invoiceDelivery: string; actDelivery: string; error: string | null; paidAt: string | null; cancellationDelivery?: string; cancellationReason?: string; replacementState?: string; replacementError?: string; targetKind?:string;targetId?:number;payer?:Payer;orderIds?:number[];messageDelivery?:string;deliveryCompleted?:boolean; }
type Document = PaperDocument;
export interface Position {orderId:number;companyId:number;companyTitle:string;amountKopecks:number;ready:boolean;paid:boolean;documentId:string|null;}
interface Choice {id:string;companyId:number;payer:Payer;primary:boolean;version?:number;lastUsedAt?:string;}
export interface Context { enabled: boolean; configured: boolean; lookupAvailable: boolean; accountLast4: string; companyId: number; companyTitle: string; amountKopecks: number; payer: Payer | null; document: Document | null; payers?:Choice[];positions?:Position[];documents?:Document[];parentInvoiceId?:number;groupUrl?:string;automaticError?:string; }
interface Request { target: PaperTarget; fallback: () => void; refresh: () => void; selectedOrder?:number; }
const request = signal<Request | null>(null);
export function openPaperInvoiceDialog(target: PaperTarget, fallback: () => void = () => {}, refresh: () => void = () => {}): void {
  request.set({ target, fallback, refresh });
}
const SETTLING_PAPER_STAGES = ['PENDING', 'INVOICE_CREATING', 'INVOICE_CREATED', 'ACT_CREATING', 'ACT_CREATED', 'FILES_READY', 'CANCEL_REQUESTED'];
const SETTLING_PAPER_DELIVERIES = ['PENDING', 'SENDING'];
/** A bank document whose state changes within seconds: being created, sent or cancelled. */
export function isSettlingPaperDocument(d: PaperDocument): boolean {
  return SETTLING_PAPER_STAGES.includes(d.stage)
    || SETTLING_PAPER_DELIVERIES.includes(d.invoiceDelivery || '')
    || SETTLING_PAPER_DELIVERIES.includes(d.actDelivery || '')
    || (d.stage === 'CANCELLED' && (d.replacementState === 'PENDING' || SETTLING_PAPER_DELIVERIES.includes(d.cancellationDelivery || '')));
}
const FAST_PAPER_POLL_MS = 3000;
const IDLE_PAPER_POLL_MS = 30_000;
const MAX_PAPER_POLL_BACKOFF_MS = 60_000;
@Component({
  selector: 'app-paper-invoice-dialog',
  imports: [FormsModule],
  template: `
    @if (opened()) {
      <div class="paper-backdrop" (click)="close()">
        <section role="dialog" aria-modal="true" aria-labelledby="paper-title" (click)="$event.stopPropagation()" (keydown.escape)="close()">
          <header><h2 id="paper-title">Счёт и акт</h2><button type="button" aria-label="Закрыть" (click)="close()" [disabled]="mutating()">×</button></header>
          @if (error()) { <p role="alert" class="error">{{ error() }}</p> }
          @if (context(); as c) {
            <p>{{ c.companyTitle }}</p>
            @if (!c.document || replacement()) { <p>Сумма: {{ (selectedAmount() / 100).toLocaleString('ru-RU') }} ₽</p> }
            @if(c.groupUrl) { <p class="hint">Группа доставки: {{c.groupUrl}}</p> }
            @if(c.automaticError) {<p class="error">Автоматический выпуск: {{c.automaticError}}</p>}
            @if (!c.configured) { <p class="error">Владелец ещё не настроил выпуск документов в Точке.</p> }
            @if (replacement() ? null : c.document; as d) {
              <h3>Счёт №{{ d.number }}</h3>
              @if(d.orderIds?.length) { <p class="hint">Заказы № {{d.orderIds?.join(', ')}}</p> }
              <p>{{ (d.amountKopecks / 100).toLocaleString('ru-RU') }} ₽ · {{ d.paidAt ? 'Оплата получена' : stage(d.stage) }}</p>
              @if (d.receivedKopecks && !d.paidAt) {
                <p>Поступило {{ (d.receivedKopecks / 100).toLocaleString('ru-RU') }} ₽ · Осталось {{ ((d.amountKopecks - d.receivedKopecks) / 100).toLocaleString('ru-RU') }} ₽</p>
              }
              <p>Счёт: {{ delivery(d.invoiceDelivery) }}. Акт: {{ delivery(d.actDelivery) }}.</p>
              @if (d.error) { <p class="error">{{ d.error }}</p> }
              <div class="actions"><button type="button" (click)="download('invoice')">Скачать счёт</button><button type="button" (click)="download('act')">Скачать акт</button></div>
              <p class="hint">{{ d.stage === 'CANCELLED' ? 'Счёт и акт отменены. Эти PDF сохранены для истории, оплачивать их не нужно.' : 'Документы и поступление оплаты проверяются автоматически. Закрытие окна не отменяет счёт и акт в банке.' }}</p>
              <div class="actions document-actions">
              <button type="button" (click)="poll()">Обновить</button>
              @if (!d.stage.startsWith('CANCEL')) { <button type="button" (click)="checkPayment()" [disabled]="busy() || d.paidAt !== null">Проверить оплату</button> }
              @if (canCancel()) { <button type="button" class="danger" (click)="cancelForm.set(true)" [disabled]="busy()">Сменить способ оплаты</button> }
              </div>
              @if (canConfirmManualDelivery()) {
                <form class="manual-delivery" (ngSubmit)="confirmManualDelivery()">
                  <h3>Отправили документы сами?</h3>
                  <p class="hint">Если клиент уже получил от вас сообщение, счёт и акт в чате, подтвердите это. Система перестанет отправлять их, отметит доставку и запустит напоминания об оплате. Оплата по-прежнему проверяется по данным банка.</p>
                  <label class="check"><input name="manualDeliveryConfirmed" type="checkbox" [(ngModel)]="manualDeliveryConfirmed" [disabled]="busy()" /> Подтверждаю: сообщение, счёт № {{ d.number }} и акт отправлены клиенту вручную</label>
                  <button type="submit" class="primary" [disabled]="busy() || !manualDeliveryConfirmed">Документы отправлены вручную</button>
                </form>
              }
              @if (d.stage === 'CANCEL_REQUESTED') { <p class="hint">Проверяем поступления и удаляем оба документа в Точке. Напоминания остановлены. При поступлении денег отмена будет передана на сверку.</p> }
              @if (d.stage === 'CANCEL_REVIEW') { <p role="alert" class="error">Отмена требует ручной сверки. Новый счёт не выпускается, автоматическое закрытие оплаты остановлено.</p> }
              @if (d.stage === 'CANCELLED') {
                <p>Причина отмены: {{ d.cancellationReason }}</p>
                <p class="hint">Уведомление заказчику: {{ d.cancellationDelivery === 'SKIPPED' ? 'не требуется — документы не отправлялись' : delivery(d.cancellationDelivery || 'PENDING') }}.</p>
                @if (d.replacementState) {
                  <p class="hint">{{ d.replacementState === 'QUEUED' ? 'Обычный счёт передан автоответчику. Получатель выбирается заново по текущим резервам. Отправка реквизитов и напоминания отслеживаются в карточке заказа.' : d.replacementState === 'REVIEW' ? 'Переход к обычному счёту остановлен. Нужна ручная сверка; новые реквизиты автоматически не отправляются.' : 'После подтверждения уведомления об отмене система подготовит обычный счёт по текущим резервам и отправит новые реквизиты. Закрытие окна не остановит процесс.' }}</p>
                  @if (d.replacementError) { <p role="alert" class="error">{{ d.replacementError }}</p> }
                } @else {
                  @if (!canReplace()) { <p class="hint">Новый счёт можно выпустить после подтверждения уведомления об отмене. Неизвестный результат доставки требует сверки.</p> }
                  <button type="button" class="primary" (click)="newInvoice()" [disabled]="busy() || !canReplace()">Выставить новый счёт</button>
                }
              }
              @if (cancelForm()) {
                <form class="cancel-form" (ngSubmit)="cancel()">
                  <h3>Замена бумажного счёта обычным</h3>
                  <p>Система проверит поступления, отменит счёт и акт в Точке и уведомит заказчика. После сверки система подготовит выбранный способ оплаты. @if(isPartDocument()) { Способ оплаты изменится только у заказов этого комплекта. Остальные счета сохранятся, а оплаты всех заказов продолжат учитываться в общем счёте. } Если обнаружено поступление или нужна сверка, отмена остановится.</p>
                  <label>После отмены выставить
                    <select name="replacementMode" [(ngModel)]="replacementMode" [disabled]="busy()">
                      <option value="AUTO_ROUTING">Подобрать способ автоматически</option>
                      <option value="EMPLOYEE_REQUISITES">Реквизиты без ссылки</option>
                      <option value="OWNER_TBANK">Банковскую ссылку владельца</option>
                    </select>
                  </label>
                  <label>Причина отмены<textarea name="cancelReason" maxlength="500" [(ngModel)]="cancelReason" [disabled]="busy()"></textarea></label>
                  <label class="check"><input name="cancelConfirmed" type="checkbox" [(ngModel)]="cancelConfirmed" [disabled]="busy()" /> Подтверждаю отмену счёта и акта и отправку обычных реквизитов</label>
                  <div class="actions"><button type="button" (click)="cancelForm.set(false)" [disabled]="busy()">Вернуться</button><button type="submit" class="danger" [disabled]="busy() || !cancelConfirmed || !cancelReason.trim()">Подтвердить отмену</button></div>
                </form>
              }
            } @else {
              <form (ngSubmit)="issue()">
                @if(isCommonSelection()) {
                  <fieldset><legend>Заказы для этого счёта</legend>
                    @for(p of c.positions || []; track p.orderId) {
                      <label class="check"><input type="checkbox" [name]="'paper-order-'+p.orderId" [checked]="selectedOrders().includes(p.orderId)" (change)="toggleOrder(p.orderId)" [disabled]="busy() || !p.ready || p.paid || !!p.documentId">{{p.companyTitle}} · заказ №{{p.orderId}} · {{(p.amountKopecks/100).toLocaleString('ru-RU')}} ₽ @if(p.paid){ · оплачен } @else if(p.documentId === 'ORDINARY'){ · обычные реквизиты } @else if(p.documentId){ · уже выставлен счёт } @else if(!p.ready){ · ещё не готов }</label>
                    }
                  </fieldset>
                }
                @if(c.payers?.length) {
                  <label>Сохранённый плательщик<select name="savedPayer" [ngModel]="selectedChoice" (ngModelChange)="chooseSaved($event)" [disabled]="busy()"><option value="">Новый плательщик / ввести ИНН</option>@for(p of c.payers || [];track p.id) {<option [value]="p.id">{{p.payer.name}} · ИНН {{p.payer.inn}} @if(p.payer.kpp){ · КПП {{p.payer.kpp}} } @if(p.primary){ · основной } </option>}</select></label>
                }
                <label>ИНН плательщика<input [disabled]="mutating()" name="inn" inputmode="numeric" maxlength="12" autocomplete="off" [ngModel]="draft.inn" (ngModelChange)="innChanged($event)" /></label>
                <button type="button" (click)="lookup()" [disabled]="busy() || !c.lookupAvailable">Найти по ИНН</button>
                @if (!c.lookupAvailable) { <p class="hint">Автозаполнение ещё не подключено. Реквизиты можно заполнить вручную.</p> }
                @if (candidates().length > 1) {
                  <label>Организация или подразделение<select [disabled]="busy()" name="candidate" (change)="choose($any($event.target).value)">
                    <option value="">Выберите плательщика</option>
                    @for (p of candidates(); track $index) { <option [value]="$index">{{ p.name }} · КПП {{ p.kpp || 'не применяется' }}</option> }
                  </select></label>
                }
                <label>Тип плательщика<select [disabled]="busy() || !!selectedChoice" name="type" [(ngModel)]="draft.type" (ngModelChange)="typeChanged()"><option value="company">Организация</option><option value="ip">ИП</option></select></label>
                <label>Полное наименование / ФИО ИП<input [disabled]="busy() || !!selectedChoice" name="name" maxlength="300" [(ngModel)]="draft.name" /></label>
                @if (draft.type === 'company') { <label>КПП<input [disabled]="busy() || !!selectedChoice" name="kpp" maxlength="9" [(ngModel)]="draft.kpp" /></label> }
                <label>Юридический адрес (если указан в реквизитах)<textarea [disabled]="busy() || !!selectedChoice" name="address" maxlength="1000" [(ngModel)]="draft.address"></textarea></label>
                @if(selectedChoice){<p class="hint">Изменить сохранённые реквизиты можно в редакторе компании. Для другого плательщика выберите «Новый плательщик».</p>}
                <p class="hint">Расчётный счёт клиента вводить не требуется. Счёт получателя: …{{ c.accountLast4 }}.</p>
                <label class="check"><input [disabled]="busy()" name="confirmed" type="checkbox" [(ngModel)]="confirmed" /> Клиент ещё не оплатил по прежним реквизитам</label>
                <p class="hint">Реквизиты сохранятся у компаний выбранных заказов. Бумажный режим и плательщик перенесутся в следующие заказы; готовые неоплаченные работы будут выставляться автоматически. Настройку можно изменить в редакторе компании.</p>
                <div class="actions"><button type="button" (click)="save()" [disabled]="busy() || isCommonSelection() || !!selectedChoice">Сохранить реквизиты</button><button class="primary" type="submit" [disabled]="busy() || !confirmed || !c.configured || (isCommonSelection() && !selectedOrders().length)">{{ busy() ? 'Сохраняем…' : 'Сформировать и отправить' }}</button></div>
              </form>
            }
          } @else { <p>Загрузка…</p> }
          <footer><button type="button" (click)="close()" [disabled]="mutating()">Закрыть</button></footer>
        </section>
      </div>
    }
  `,
  styles: [`
    :host{--paper-bg:#fff;--paper-text:#252938;--paper-muted:#596175;--paper-input:#fff;--paper-border:#cbd0da;--paper-button:#f6f7fa;--paper-error-bg:#fff1f2;--paper-error-text:#9c2037;--paper-error-border:#efb4b7}
    :host-context(body.otziv-dark-theme){--paper-bg:#252b30;--paper-text:#edf1f5;--paper-muted:#b9c5d1;--paper-input:#1c2227;--paper-border:#596775;--paper-button:#343e47;--paper-error-bg:#482b34;--paper-error-text:#ffd5dc;--paper-error-border:#a65a70;color-scheme:dark}
    .paper-backdrop{position:fixed;inset:0;background:#14182799;z-index:100000;display:flex;align-items:center;justify-content:center;padding:16px}
    section{background:var(--paper-bg);color:var(--paper-text);border:1px solid var(--paper-border);border-radius:20px;padding:24px;width:min(580px,100%);max-height:92dvh;overflow:auto;box-shadow:0 20px 80px #0004}
    *,*::before,*::after{box-sizing:border-box}header{display:flex;align-items:center;justify-content:space-between;gap:12px}h2{margin:0}h3,p{overflow-wrap:anywhere;line-height:1.5}header button{font-size:28px;border:0;background:transparent;flex:0 0 44px}
    .actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;align-items:stretch}.document-actions{margin-top:16px}.document-actions .danger{grid-column:1/-1}
    label{display:block;margin:16px 0 6px;font-size:14px;font-weight:600}input,select,textarea{display:block;width:100%;box-sizing:border-box;padding:12px;margin-top:6px;border:1px solid var(--paper-border);border-radius:9px;background:var(--paper-input);color:var(--paper-text);font:inherit}textarea{min-height:70px}
    .check{display:flex;align-items:center;gap:10px;font-weight:400}.check input{width:20px;flex-shrink:0}
    button{padding:12px 16px;border:1px solid var(--paper-border);border-radius:12px;background:var(--paper-button);color:var(--paper-text);cursor:pointer;min-height:48px;min-width:0;max-width:100%;margin:0;font:inherit;line-height:1.35;white-space:normal}button:disabled{opacity:.6;cursor:default}.primary{background:#ba4a25;color:white;border-color:#ba4a25}
    .danger{background:var(--paper-error-bg);color:var(--paper-error-text);border-color:var(--paper-error-border)}.manual-delivery{margin-top:20px;padding-top:12px;border-top:1px solid var(--paper-border)}.manual-delivery button{width:100%}.cancel-form{margin-top:20px;padding-top:12px;border-top:1px solid var(--paper-border)}button:focus-visible,input:focus-visible,textarea:focus-visible,select:focus-visible{outline:3px solid #dc8e67;outline-offset:2px}
    .hint{font-size:13px;color:var(--paper-muted);line-height:1.5}.error{padding:12px;border:1px solid var(--paper-error-border);background:var(--paper-error-bg);border-radius:9px;color:var(--paper-error-text)}footer{margin-top:20px;text-align:right}
    @media(max-width:600px){.paper-backdrop{padding:12px;align-items:center}section{padding:24px 20px;max-height:calc(100dvh - 24px)}footer button{width:100%}}
    @media(max-width:360px){.actions{grid-template-columns:1fr}}
  `]
})
export class PaperInvoiceDialogComponent implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '';
  readonly opened = signal(false);
  readonly context = signal<Context | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly mutating = signal(false);
  readonly cancelForm = signal(false);
  readonly replacement = signal(false);
  cancelReason = '';
  replacementMode = 'AUTO_ROUTING';
  cancelConfirmed = false;
  manualDeliveryConfirmed = false;
  private lookupTicket = 0;
  private pollTicket = 0;
  private pollFailed = false;
  readonly candidates = signal<Payer[]>([]);
  draft: Payer = { type: 'company', name: '', inn: '', kpp: '', address: '' };
  confirmed = false;
  selectedChoice = '';
  readonly selectedOrders = signal<number[]>([]);
  private requestKey = '';
  private ticket = 0;
  private pollFailures = 0;
  // Fast while the document is being prepared or sent; a document waiting for
  // payment (possibly for days) is re-read every 30 s, nothing while hidden.
  private readonly poller = new VisibilityAwarePoller(() => this.poll(), () => this.pollDelayMs());
  private lookupTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly watch = effect(() => {
    const r = request();
    if (!r) return;
    this.stop(); this.busy.set(false); this.mutating.set(false); ++this.lookupTicket; const ticket = ++this.ticket;
    this.opened.set(true); this.context.set(null); this.error.set(''); this.candidates.set([]); this.confirmed = false;
    this.cancelForm.set(false);this.replacement.set(false);this.cancelReason='';this.replacementMode='AUTO_ROUTING';this.cancelConfirmed=false;this.manualDeliveryConfirmed=false;
    this.selectedOrders.set([]);this.selectedChoice='';this.requestKey=crypto.randomUUID();
    this.http.get<Context>(this.path() + '/context').subscribe({
      next: c => {
        if (ticket !== this.ticket) return;
        if (!c.enabled) { this.opened.set(false); request.set(null); r.fallback(); return; }
        if(r.target.kind==='ORDER' && c.parentInvoiceId){
          const order=r.target.id;
          request.set({...r,target:{kind:'COMMON_INVOICE',id:c.parentInvoiceId},selectedOrder:order});return;
        }
        if(r.target.kind==='COMMON_INVOICE' && (!c.document || c.document.stage==='CANCELLED')) c={...c,document:null};
        this.context.set(c);
        if(r.selectedOrder && c.positions?.some(p=>p.orderId===r.selectedOrder && p.ready && !p.paid && !p.documentId))this.selectedOrders.set([r.selectedOrder]);
        this.draft = r.target.kind!=='COMMON_INVOICE' && c.payer ? { ...c.payer } : { type: 'company', name: '', inn: '', kpp: '', address: '' };
        if(r.target.kind!=='COMMON_INVOICE')this.selectedChoice=c.payers?.find(p=>p.primary)?.id || '';
        if (c.document) this.startPolling();
      }, error: e => { if (ticket === this.ticket) this.failure(e); }
    });
  });
  private path(): string { const t = request()!.target; return this.baseUrl + '/api/paper-invoices/' + t.kind + '/' + t.id; }
  isCommonSelection(): boolean {return request()?.target.kind==='COMMON_INVOICE' && !this.context()?.document;}
  isPartDocument():boolean {return this.context()?.document?.targetKind==='COMMON_PART' || request()?.target.kind==='COMMON_PART';}
  selectedAmount():number {return this.isCommonSelection()?(this.context()?.positions || []).filter(p=>this.selectedOrders().includes(p.orderId)).reduce((sum,p)=>sum+p.amountKopecks,0):(this.context()?.amountKopecks || 0);}
  toggleOrder(id:number):void {
    if(this.busy())return;const p=this.context()?.positions?.find(p=>p.orderId===id);if(!p || !p.ready || p.paid || p.documentId)return;
    this.selectedOrders.update(ids=>ids.includes(id)?ids.filter(i=>i!==id):[...ids,id]);
  }
  chooseSaved(id:string):void {
    if(this.busy())return;const saved=this.context()?.payers?.find(p=>p.id===id);++this.lookupTicket;this.selectedChoice=id;this.candidates.set([]);
    this.draft=saved?{...saved.payer}:{type:'company',name:'',inn:'',kpp:'',address:''};
  }
  private failure(e: any): void { this.busy.set(false); this.mutating.set(false); this.error.set(e?.error?.detail || e?.error?.message || 'Не удалось выполнить действие. Проверьте соединение и обновите данные.'); }
  innChanged(value: string): void {
    if (this.mutating()) return;
    ++this.lookupTicket;
    this.selectedChoice='';
    this.draft = { type: 'company', name: '', inn: value.replace(/\D/g, ''), kpp: '', address: '' };
    this.busy.set(false); this.error.set(''); this.candidates.set([]);
    if (this.lookupTimer) clearTimeout(this.lookupTimer);
    if ([10, 12].includes(this.draft.inn.length) && this.context()?.lookupAvailable)
      this.lookupTimer = setTimeout(() => this.lookup(), 600);
  }
  typeChanged(): void { if (this.draft.type === 'ip') this.draft.kpp = ''; }
  lookup(): void {
    if (this.mutating() || !this.opened()) return;
    if (this.lookupTimer) clearTimeout(this.lookupTimer); this.lookupTimer = null;
    const inn = this.draft.inn; const ticket = this.ticket; const lookupTicket = ++this.lookupTicket;
    this.error.set(''); this.busy.set(true);
    this.http.get<{ candidates: Payer[] }>(this.path() + '/lookup', { params: { inn } }).subscribe({
      next: result => {
        if (ticket !== this.ticket || lookupTicket !== this.lookupTicket || inn !== this.draft.inn) return;
        this.busy.set(false); this.candidates.set(result.candidates);
        if (result.candidates.length === 1) this.draft = { ...result.candidates[0] };
        else if (!result.candidates.length) this.error.set('Действующая организация или ИП не найдены. Проверьте ИНН; реквизиты можно заполнить вручную.');
      }, error: e => { if (ticket === this.ticket && lookupTicket === this.lookupTicket && inn === this.draft.inn) this.failure(e); }
    });
  }
  choose(index: string): void { const p = this.candidates()[Number(index)]; if (index !== '' && p) this.draft = { ...p }; }
  private beginMutation(): number | null {
    if (this.busy() || !this.opened()) return null;
    if (this.lookupTimer) clearTimeout(this.lookupTimer); this.lookupTimer = null;
    ++this.lookupTicket;
    this.pollFailed=false;this.mutating.set(true); this.busy.set(true); this.error.set('');
    return this.ticket;
  }
  private finishMutation(ticket: number): boolean {
    if (ticket !== this.ticket) return false;
    this.busy.set(false); this.mutating.set(false); return true;
  }
  save(): void {
    const ticket = this.beginMutation(); if (ticket === null) return;
    this.http.put(this.path() + '/payer', { ...this.draft }).subscribe({
      next: () => { this.finishMutation(ticket); },
      error: e => { if (ticket === this.ticket) this.failure(e); }
    });
  }
  issue(): void {
    if (!this.confirmed) return;
    if(this.isCommonSelection() && !this.selectedOrders().length) {this.error.set('Выберите заказы для счёта');return;}
    const ticket = this.beginMutation(); if (ticket === null) return;
    const choice=this.context()?.payers?.find(p=>p.id===this.selectedChoice && !p.id.startsWith('history:'));
    this.http.post<Document>(this.path() + '/issue', { payer: { ...this.draft }, confirmedUnpaid: true,orderIds:this.selectedOrders(),requestKey:this.requestKey,payerChoiceId:choice?.id,payerChoiceVersion:choice?.version }).subscribe({
      next: document => {
        if (!this.finishMutation(ticket)) return;
        this.replacement.set(false);
        this.context.update(c => c ? { ...c, document } : c); this.startPolling();
      }, error: e => { if (ticket === this.ticket) this.failure(e); }
    });
  }
  poll(): void {
    const id = this.context()?.document?.id; const ticket = this.ticket; const pollTicket = ++this.pollTicket;
    if (!id) return;
    this.http.get<Document>(this.baseUrl + '/api/paper-invoices/documents/' + id).subscribe({
      next: document => { if (ticket === this.ticket && pollTicket === this.pollTicket) { this.pollFailures = 0; if(this.pollFailed && !this.mutating()) {this.error.set('');this.pollFailed=false;} this.context.update(c => c ? { ...c, document } : c); if (['PAID','CANCEL_REVIEW'].includes(document.stage) || (document.stage==='CANCELLED' && document.replacementState!=='PENDING' && ['SENT','SKIPPED','UNKNOWN'].includes(document.cancellationDelivery || ''))) this.stop(); else this.poller.reschedule(); } },
      error: e => { if (ticket === this.ticket && pollTicket === this.pollTicket) { this.pollFailures += 1; this.poller.reschedule(); if (!this.mutating()) {this.pollFailed=true;this.failure(e);} } }
    });
  }
  private startPolling(): void { this.poller.start(); }
  private pollDelayMs(): number {
    if (this.pollFailures > 0) return backoffDelayMs(FAST_PAPER_POLL_MS, this.pollFailures, MAX_PAPER_POLL_BACKOFF_MS);
    const document = this.context()?.document;
    return document && !isSettlingPaperDocument(document) ? IDLE_PAPER_POLL_MS : FAST_PAPER_POLL_MS;
  }
  canCancel(): boolean {
    const d=this.context()?.document;
    return !!d && !d.paidAt && !d.receivedKopecks && ['PENDING','INVOICE_CREATED','ACT_CREATED','FILES_READY','WAITING_PAYMENT','NEEDS_REVIEW'].includes(d.stage);
  }
  cancel(): void {
    const d=this.context()?.document;if(!d || !this.canCancel() || !this.cancelConfirmed || !this.cancelReason.trim()) return;
    const ticket=this.beginMutation();if(ticket===null) return;++this.pollTicket;
    this.http.post<Document>(this.baseUrl+'/api/paper-invoices/documents/'+d.id+'/cancel',{reason:this.cancelReason.trim(),confirmed:true,replaceWithOrdinary:true,
      ...(this.replacementMode === 'AUTO_ROUTING' ? {} : {replacementMode:this.replacementMode})}).subscribe({
      next: document => {if(!this.finishMutation(ticket)) return;++this.pollTicket;this.cancelForm.set(false);this.context.update(c=>c?{...c,document}:c);this.startPolling();},
      error: e => {if(ticket===this.ticket) this.failure(e);}
    });
  }
  /**
   * Automatic delivery ran into trouble (an unconfirmed result or a recorded failure) and no send is in
   * flight: the manager may confirm a manual send. A routine send in progress shows no such offer.
   */
  canConfirmManualDelivery(): boolean {
    const d=this.context()?.document;
    if(!d || d.paidAt || d.stage !== 'FILES_READY' || d.deliveryCompleted || this.cancelForm()) return false;
    const states=[d.messageDelivery || 'PENDING', d.invoiceDelivery, d.actDelivery];
    return !states.includes('SENDING') && states.some(state => state !== 'SENT')
      && (states.includes('UNKNOWN') || !!d.error);
  }
  confirmManualDelivery(): void {
    const d=this.context()?.document;if(!d || !this.manualDeliveryConfirmed || !this.canConfirmManualDelivery()) return;
    const ticket=this.beginMutation();if(ticket===null) return;++this.pollTicket;
    this.http.post<Document>(this.baseUrl+'/api/paper-invoices/documents/'+d.id+'/manual-delivery',{}).subscribe({
      next: document => {if(!this.finishMutation(ticket)) return;++this.pollTicket;this.manualDeliveryConfirmed=false;this.context.update(c=>c?{...c,document}:c);this.startPolling();},
      error: e => {if(ticket===this.ticket) this.failure(e);}
    });
  }
  newInvoice(): void {
    if(this.busy() || !this.canReplace()) return;
    if(this.isPartDocument()) {
      const current=request();const parent=current?.target.kind==='COMMON_INVOICE'?current.target.id:this.context()?.parentInvoiceId;
      if(parent && current)openPaperInvoiceDialog({kind:'COMMON_INVOICE',id:parent},current.fallback,current.refresh);
      return;
    }
    this.stop();++this.pollTicket;this.confirmed=false;this.replacement.set(true);
  }
  canReplace(): boolean {
    const document=this.context()?.document;
    return document?.stage==='CANCELLED' && !document.replacementState && ['SENT','SKIPPED'].includes(document.cancellationDelivery || '');
  }
  checkPayment(): void {
    const ticket = this.beginMutation(); if (ticket === null) return;
    this.http.post(this.path() + '/reconcile', {}).subscribe({
      next: () => { if (this.finishMutation(ticket)) this.poll(); },
      error: e => { if (ticket === this.ticket) this.failure(e); }
    });
  }
  download(type: string): void {
    if (this.busy()) return;
    const ticket = this.ticket;
    const d = this.context()?.document; if (!d) return;
    this.http.get(this.baseUrl + '/api/paper-invoices/documents/' + d.id + '/' + type + '.pdf', { responseType: 'blob' }).subscribe({
      next: blob => { if (ticket !== this.ticket) return; const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = type + '-' + d.number + '.pdf'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); },
      error: e => { if (ticket === this.ticket && !this.mutating()) this.failure(e); }
    });
  }
  stage(value: string): string {
    if(value.startsWith('CANCEL')) return ({CANCEL_REQUESTED:'Отмена в процессе',CANCELLED:'Счёт и акт отменены',CANCEL_REVIEW:'Отмена требует сверки'} as Record<string,string>)[value] || 'Отмена требует сверки';
    return ({ PENDING: 'Ожидает формирования', INVOICE_CREATING: 'Формируется счёт', INVOICE_CREATED: 'Формируется акт', ACT_CREATING: 'Формируется акт', ACT_CREATED: 'Получаем PDF', FILES_READY: 'Отправляем документы', WAITING_PAYMENT: 'Ожидает оплаты', PAID: 'Оплата получена' } as Record<string, string>)[value] || 'Требуется сверка';
  }
  delivery(value: string): string { return ({ SENT: 'отправлен', PENDING: 'ожидает отправки', SENDING: 'отправляется', UNKNOWN: 'доставку нужно проверить' } as Record<string, string>)[value] || 'проверяется'; }
  close(): void { if (this.mutating()) return; this.busy.set(false); ++this.lookupTicket; const r = request(); this.stop(); ++this.ticket; this.opened.set(false); request.set(null); if (this.context()?.document) r?.refresh(); }
  private stop(): void { this.poller.stop(); this.pollFailures = 0; if (this.lookupTimer) clearTimeout(this.lookupTimer); this.lookupTimer = null; }
  ngOnDestroy(): void { ++this.ticket; ++this.lookupTicket; this.stop(); }
}
