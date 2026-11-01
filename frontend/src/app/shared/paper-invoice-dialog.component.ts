import { Component, effect, inject, OnDestroy, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

export interface PaperTarget { kind: 'ORDER' | 'COMMON_INVOICE'; id: number; }
interface Payer { type: string; name: string; inn: string; kpp: string; address: string; }
interface Document { id: string; number: string; amountKopecks: number; stage: string; invoiceDelivery: string; actDelivery: string; error: string | null; paidAt: string | null; }
interface Context { enabled: boolean; configured: boolean; lookupAvailable: boolean; accountLast4: string; companyId: number; companyTitle: string; amountKopecks: number; payer: Payer | null; document: Document | null; }
interface Request { target: PaperTarget; fallback: () => void; refresh: () => void; }
const request = signal<Request | null>(null);
export function openPaperInvoiceDialog(target: PaperTarget, fallback: () => void = () => {}, refresh: () => void = () => {}): void {
  request.set({ target, fallback, refresh });
}
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
            @if (!c.document) { <p>Сумма: {{ (c.amountKopecks / 100).toLocaleString('ru-RU') }} ₽</p> }
            @if (!c.configured) { <p class="error">Владелец ещё не настроил выпуск документов в Точке.</p> }
            @if (c.document; as d) {
              <h3>Счёт №{{ d.number }}</h3>
              <p>{{ (d.amountKopecks / 100).toLocaleString('ru-RU') }} ₽ · {{ d.paidAt ? 'Оплата получена' : stage(d.stage) }}</p>
              <p>Счёт: {{ delivery(d.invoiceDelivery) }}. Акт: {{ delivery(d.actDelivery) }}.</p>
              @if (d.error) { <p class="error">{{ d.error }}</p> }
              <div class="actions"><button type="button" (click)="download('invoice')">Скачать счёт</button><button type="button" (click)="download('act')">Скачать акт</button></div>
              <p class="hint">Документы и поступление оплаты проверяются автоматически. Повторно создавать счёт не нужно. Закрытие окна не отменяет счёт и акт в банке.</p>
              <button type="button" (click)="poll()">Обновить</button>
              <button type="button" (click)="checkPayment()" [disabled]="busy() || d.paidAt !== null">Проверить оплату</button>
            } @else {
              <form (ngSubmit)="issue()">
                <label>ИНН плательщика<input [disabled]="mutating()" name="inn" inputmode="numeric" maxlength="12" autocomplete="off" [ngModel]="draft.inn" (ngModelChange)="innChanged($event)" /></label>
                <button type="button" (click)="lookup()" [disabled]="busy() || !c.lookupAvailable">Найти по ИНН</button>
                @if (!c.lookupAvailable) { <p class="hint">Автозаполнение ещё не подключено. Реквизиты можно заполнить вручную.</p> }
                @if (candidates().length > 1) {
                  <label>Организация или подразделение<select [disabled]="busy()" name="candidate" (change)="choose($any($event.target).value)">
                    <option value="">Выберите плательщика</option>
                    @for (p of candidates(); track $index) { <option [value]="$index">{{ p.name }} · КПП {{ p.kpp || 'не применяется' }}</option> }
                  </select></label>
                }
                <label>Тип плательщика<select [disabled]="busy()" name="type" [(ngModel)]="draft.type" (ngModelChange)="typeChanged()"><option value="company">Организация</option><option value="ip">ИП</option></select></label>
                <label>Полное наименование / ФИО ИП<input [disabled]="busy()" name="name" maxlength="300" [(ngModel)]="draft.name" /></label>
                @if (draft.type === 'company') { <label>КПП<input [disabled]="busy()" name="kpp" maxlength="9" [(ngModel)]="draft.kpp" /></label> }
                <label>Юридический адрес (если указан в реквизитах)<textarea [disabled]="busy()" name="address" maxlength="1000" [(ngModel)]="draft.address"></textarea></label>
                <p class="hint">Расчётный счёт клиента вводить не требуется. Счёт получателя: …{{ c.accountLast4 }}.</p>
                <label class="check"><input [disabled]="busy()" name="confirmed" type="checkbox" [(ngModel)]="confirmed" /> Клиент ещё не оплатил по прежним реквизитам</label>
                <p class="hint">Счёт и акт формируются по выполненным работам. Введённые реквизиты сохранятся для следующих заказов.</p>
                <div class="actions"><button type="button" (click)="save()" [disabled]="busy()">Сохранить реквизиты</button><button class="primary" type="submit" [disabled]="busy() || !confirmed || !c.configured">{{ busy() ? 'Сохраняем…' : 'Сформировать и отправить' }}</button></div>
              </form>
            }
          } @else { <p>Загрузка…</p> }
          <footer><button type="button" (click)="close()" [disabled]="mutating()">Закрыть</button></footer>
        </section>
      </div>
    }
  `,
  styles: [`
    .paper-backdrop{position:fixed;inset:0;background:#14182799;z-index:100000;display:flex;align-items:center;justify-content:center;padding:16px}
    section{background:#fff;color:#252938;border-radius:20px;padding:24px;width:min(580px,100%);max-height:92dvh;overflow:auto;box-shadow:0 20px 80px #0004}
    header,.actions{display:flex;align-items:center;justify-content:space-between;gap:12px}h2{margin:0}header button{font-size:28px;border:0;background:transparent}
    label{display:block;margin:16px 0 6px;font-size:14px;font-weight:600}input,select,textarea{display:block;width:100%;box-sizing:border-box;padding:12px;margin-top:6px;border:1px solid #cbd0da;border-radius:9px;background:#fff;color:#252938;font:inherit}textarea{min-height:70px}
    .check{display:flex;align-items:center;gap:10px;font-weight:400}.check input{width:20px;flex-shrink:0}
    button{padding:11px 16px;border:1px solid #cbd0da;border-radius:10px;background:#f6f7fa;color:#252938;cursor:pointer;min-height:44px}button:disabled{opacity:.5;cursor:default}.primary{background:#ba4a25;color:white;border-color:#ba4a25}
    .hint{font-size:13px;color:#596175;line-height:1.5}.error{padding:12px;border:1px solid #efb4b7;background:#fff1f2;border-radius:9px;color:#9c2037}footer{margin-top:20px;text-align:right}
    @media(max-width:600px){.paper-backdrop{padding:8px;align-items:flex-end}section{padding:20px 16px}.actions{flex-wrap:wrap}.actions button{flex:1}}
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
  private lookupTicket = 0;
  private pollTicket = 0;
  readonly candidates = signal<Payer[]>([]);
  draft: Payer = { type: 'company', name: '', inn: '', kpp: '', address: '' };
  confirmed = false;
  private ticket = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lookupTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly watch = effect(() => {
    const r = request();
    if (!r) return;
    this.stop(); this.busy.set(false); this.mutating.set(false); ++this.lookupTicket; const ticket = ++this.ticket;
    this.opened.set(true); this.context.set(null); this.error.set(''); this.candidates.set([]); this.confirmed = false;
    this.http.get<Context>(this.path() + '/context').subscribe({
      next: c => {
        if (ticket !== this.ticket) return;
        if (!c.enabled) { this.opened.set(false); request.set(null); r.fallback(); return; }
        this.context.set(c);
        this.draft = c.payer ? { ...c.payer } : { type: 'company', name: '', inn: '', kpp: '', address: '' };
        if (c.document) this.startPolling();
      }, error: e => { if (ticket === this.ticket) this.failure(e); }
    });
  });
  private path(): string { const t = request()!.target; return this.baseUrl + '/api/paper-invoices/' + t.kind + '/' + t.id; }
  private failure(e: any): void { this.busy.set(false); this.mutating.set(false); this.error.set(e?.error?.detail || e?.error?.message || 'Не удалось выполнить действие. Проверьте соединение и обновите данные.'); }
  innChanged(value: string): void {
    if (this.mutating()) return;
    ++this.lookupTicket;
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
    this.mutating.set(true); this.busy.set(true); this.error.set('');
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
    const ticket = this.beginMutation(); if (ticket === null) return;
    this.http.post<Document>(this.path() + '/issue', { payer: { ...this.draft }, confirmedUnpaid: true }).subscribe({
      next: document => {
        if (!this.finishMutation(ticket)) return;
        this.context.update(c => c ? { ...c, document } : c); this.startPolling();
      }, error: e => { if (ticket === this.ticket) this.failure(e); }
    });
  }
  poll(): void {
    const id = this.context()?.document?.id; const ticket = this.ticket; const pollTicket = ++this.pollTicket;
    if (!id) return;
    this.http.get<Document>(this.baseUrl + '/api/paper-invoices/documents/' + id).subscribe({
      next: document => { if (ticket === this.ticket && pollTicket === this.pollTicket) { this.context.update(c => c ? { ...c, document } : c); if (document.stage === 'PAID') this.stop(); } },
      error: e => { if (ticket === this.ticket && pollTicket === this.pollTicket && !this.mutating()) this.failure(e); }
    });
  }
  private startPolling(): void { if (!this.timer) this.timer = setInterval(() => this.poll(), 3000); }
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
    return ({ PENDING: 'Ожидает формирования', INVOICE_CREATING: 'Формируется счёт', INVOICE_CREATED: 'Формируется акт', ACT_CREATING: 'Формируется акт', ACT_CREATED: 'Получаем PDF', FILES_READY: 'Отправляем документы', WAITING_PAYMENT: 'Ожидает оплаты', PAID: 'Оплата получена' } as Record<string, string>)[value] || 'Требуется сверка';
  }
  delivery(value: string): string { return ({ SENT: 'отправлен', PENDING: 'ожидает отправки', SENDING: 'отправляется', UNKNOWN: 'доставку нужно проверить' } as Record<string, string>)[value] || 'проверяется'; }
  close(): void { if (this.mutating()) return; this.busy.set(false); ++this.lookupTicket; const r = request(); this.stop(); ++this.ticket; this.opened.set(false); request.set(null); if (this.context()?.document) r?.refresh(); }
  private stop(): void { if (this.timer) clearInterval(this.timer); this.timer = null; if (this.lookupTimer) clearTimeout(this.lookupTimer); this.lookupTimer = null; }
  ngOnDestroy(): void { ++this.ticket; ++this.lookupTicket; this.stop(); }
}
