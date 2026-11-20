import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { appEnvironment } from '../core/app-environment';

interface Contact {
  companyId: number; title: string; companyStatus: string; platform: string;
  description: string; checkStatus: string; checkedAt: string | null; excluded: boolean;
}
interface DeliveryReview {
  stateId: number; companyId: number | null; title: string; managerId: number;
  scenario: string; preparedAt: string | null; targetUrl: string;
}
type ContactTarget = { companyId: number; title: string };

@Component({
  selector: 'app-inactive-company-contacts', standalone: true, imports: [DatePipe, RouterLink],
  template: `
    <section class="contacts">
      <h2>Недоступные чаты компаний на стопе</h2>
      <p>Здесь компании без действующих заказов и открытых заявок, для которых подтверждена проблема с чатом.
        Рассылка им приостановлена. Если появится действующий заказ, проблема попадёт в замечания менеджера.</p>
      <p>После исправления контакта проверьте доступ. Напоминания продолжатся по расписанию автоответчика;
        старые сообщения с неизвестным результатом повторно не отправляются.</p>
      <div class="toolbar">
        <label>Компания <input aria-label="Найти компанию" [value]="search()" (input)="search.set($any($event.target).value)" /></label>
        <button type="button" (click)="load()" [disabled]="loading()">{{ loading() ? 'Загрузка…' : 'Обновить список' }}</button>
      </div>
      @if (error()) { <p role="alert">{{ error() }}</p> }
      @if (notice()) { <p role="status">{{ notice() }}</p> }
      <p>Компаний: {{ filtered().length }}</p>
      @for (row of filtered(); track row.companyId) {
        <article>
          <h3>{{ row.title }} <small>№{{ row.companyId }}</small></h3>
          <p>{{ row.companyStatus }} · {{ row.platform }} · <strong>Рассылка приостановлена</strong></p>
          <p>{{ row.description }}</p>
          @if (row.checkStatus === 'UNKNOWN') { <p>Последняя проверка не дала ответа. Прежнее ограничение сохранено.</p> }
          @if (row.checkedAt) { <p>Проверено: {{ row.checkedAt | date:'dd.MM.yyyy HH:mm' }}</p> }
          <div class="actions">
            <a routerLink="/companies" [queryParams]="{keyword: row.title}">Открыть компанию</a>
            <button type="button" (click)="pending.set(row)" [disabled]="checking() !== null">Проверить доступ</button>
          </div>
          @if (pending()?.companyId === row.companyId) {
            <div class="confirmation" role="group" aria-label="Проверка доступа">
              <p>Проверить доступ к чату «{{ row.title }}»? Клиенту ничего не отправится.
                При подтверждённом доступе ограничение снимется.</p>
              <button type="button" (click)="check(row)" [disabled]="checking() !== null">{{ checking() === row.companyId ? 'Проверяю…' : 'Проверить' }}</button>
              <button type="button" (click)="pending.set(null)" [disabled]="checking() !== null">Отмена</button>
            </div>
          }
        </article>
      } @empty { @if (!loading() && !error()) { <p>Компаний с подтверждённо недоступным чатом в этом списке нет.</p> } }
      <h2>Сверка прежних отправок</h2>
      <p>Исторические отправки компаний без действующей работы проверяет администратор.
        Они не входят в обязательные замечания менеджера. При появлении заказа или открытой заявки проблема вернётся менеджеру.</p>
      <p>Это не подтверждение доставки и не запрет всех будущих напоминаний. Повтор старого сообщения заблокирован.
        Ограничение рассылки устанавливается отдельно, только при подтверждённо недоступном чате компании на стопе.</p>
      @if (reviewError()) { <p role="alert">{{ reviewError() }}</p> }
      <p>Операций: {{ filteredReviews().length }}</p>
      @for (row of filteredReviews(); track row.stateId) {
        <article>
          <h3>{{ row.title }} <small>операция №{{ row.stateId }}</small></h3>
          <p>{{ row.scenario }}</p>
          @if (row.preparedAt) { <p>Исходная попытка: {{ row.preparedAt | date:'dd.MM.yyyy HH:mm' }}</p> }
          <p>Исход не подтверждён. Сверьте исходный чат и журнал операции; для счёта также поступления.
            Проверка доступа к текущему чату не подтверждает доставку старого сообщения и не разрешает его повтор.</p>
          <div class="actions">
            <a [routerLink]="'/companies'" [queryParams]="{keyword: row.title}">Открыть компанию</a>
            @if (row.companyId !== null) {
              <button type="button" (click)="pending.set({companyId: row.companyId!, title: row.title})" [disabled]="checking() !== null">Проверить доступ</button>
            }
          </div>
          @if (row.companyId !== null && pending()?.companyId === row.companyId) {
            <div class="confirmation" role="group" aria-label="Проверка доступа">
              <p>Проверить доступ к чату «{{ row.title }}»? Клиенту ничего не отправится.
                Подтверждённо закрытый чат компании на стопе будет исключён из рассылки.</p>
              <button type="button" (click)="check({companyId: row.companyId!, title: row.title})" [disabled]="checking() !== null">{{ checking() === row.companyId ? 'Проверяю…' : 'Проверить' }}</button>
              <button type="button" (click)="pending.set(null)" [disabled]="checking() !== null">Отмена</button>
            </div>
          }
        </article>
      } @empty { @if (!loading() && !reviewError()) { <p>Прежних отправок для административной сверки нет.</p> } }
    </section>
  `,
  styles: [`
    :host { display:block; color:var(--otziv-dark,#363949); }
    .contacts { padding:1rem; max-width:72rem; margin:auto; }
    h2,h3,strong { color:var(--otziv-dark,#363949); } h3 { margin:0; } small { font-weight:normal; }
    p { line-height:1.55; margin:.7rem 0; } article { padding:1.2rem; margin:1rem 0; border:1px solid var(--otziv-surface-border-neutral,#dce1eb); border-radius:1rem; background:var(--otziv-white,#fff); }
    .toolbar,.actions { display:flex; gap:1rem; flex-wrap:wrap; align-items:center; }
    label { display:flex; gap:.5rem; align-items:center; flex-wrap:wrap; }
    input,button,a { color:inherit; border:1px solid var(--otziv-info,#7380a1); border-radius:.65rem; padding:.65rem .9rem; background:var(--otziv-white,#fff); font:inherit; }
    input { max-width:100%; box-sizing:border-box; } button,a { cursor:pointer; } button:disabled { opacity:.6; cursor:wait; }
    .confirmation { margin-top:1rem; padding:1rem; border:1px solid var(--otziv-primary,#7380ec); border-radius:.8rem; }
    .confirmation button { margin-right:.5rem; } [role=alert] { color:var(--otziv-danger,#d52757); }
  `]
})
export class InactiveCompanyContactsComponent {
  private readonly http = inject(HttpClient);
  private readonly url = `${appEnvironment.apiBaseUrl}/api/admin/client-messages/contacts`;
  readonly rows = signal<Contact[]>([]);
  readonly reviews = signal<DeliveryReview[]>([]);
  readonly reviewError = signal('');
  readonly search = signal(''); readonly loading = signal(false); readonly error = signal('');
  readonly notice = signal(''); readonly checking = signal<number | null>(null); readonly pending = signal<ContactTarget | null>(null);
  readonly filtered = computed(() => this.rows().filter(row => `${row.title} ${row.companyId}`.toLowerCase().includes(this.search().trim().toLowerCase())));
  readonly filteredReviews = computed(() => this.reviews().filter(row => `${row.title} ${row.companyId} ${row.stateId}`.toLowerCase().includes(this.search().trim().toLowerCase())));
  constructor() { this.load(); }
  load(): void {
    this.loading.set(true); this.error.set('');
    this.http.get<Contact[]>(this.url).subscribe({ next: rows => { this.rows.set(rows); this.loading.set(false); },
      error: () => { this.error.set('Не удалось загрузить список. Повторите проверку.'); this.loading.set(false); } });
    this.reviewError.set('');
    this.http.get<DeliveryReview[]>(`${appEnvironment.apiBaseUrl}/api/admin/manager-control/delivery-reviews`).subscribe({
      next: rows => this.reviews.set(rows),
      error: () => this.reviewError.set('Не удалось загрузить сверку прежних отправок. Обновите список.')
    });
  }
  check(row: ContactTarget): void {
    if (this.checking() !== null) return;
    this.checking.set(row.companyId); this.error.set(''); this.notice.set('');
    this.http.post<Contact>(`${this.url}/${row.companyId}/recheck`, {}).subscribe({
      next: result => {
        this.notice.set(`${row.title}: ${result.description}`); this.pending.set(null); this.checking.set(null); this.load();
      }, error: () => { this.error.set('Проверка недоступна. Ограничение не снято; клиенту ничего не отправлялось.'); this.checking.set(null); }
    });
  }
}
