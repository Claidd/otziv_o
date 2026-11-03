import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { finalize } from 'rxjs';
import { appEnvironment } from '../../../core/app-environment';
import { apiErrorMessage } from '../../../shared/api-error-message';

interface ArchiveCheck {
  stateId: number; orderId: number; company: string; channel: string | null; outcome: string | null;
  reason: string | null; checkedAt: string | null; nextCheckAt: string | null;
}

@Component({
  selector: 'app-review-archive-checks',
  imports: [DatePipe],
  template: `
    <section class="archive-checks" aria-label="Проверка автоархива">
      <div class="heading"><div><h3>Проверка автоархива</h3>
        <p>После семи доставленных напоминаний система проверяет переписку в канале заказа, оплату и срок ожидания. Время — иркутское.</p></div>
        <button type="button" (click)="load()" [disabled]="busy()">Обновить проверки</button></div>
      @if (error()) { <p role="alert">{{ error() }}</p> }
      @if (message()) { <p role="status">{{ message() }}</p> }
      <div class="rows">
        @for (row of rows(); track row.stateId) {
          <article>
            <div><strong>Заказ №{{ row.orderId }} · {{ row.company }}</strong>
              <p>{{ row.channel || 'Канал ещё не проверен' }} · {{ label(row.outcome) }}</p>
              <p>{{ row.reason || (row.outcome === 'ARCHIVED' ? 'Заказ перенесён в архив; уведомления сохранены в очереди.' : 'Ожидает автоматической проверки.') }}</p>
              <small>Проверено: {{ row.checkedAt ? (row.checkedAt | date:'dd.MM.yyyy HH:mm:ss') : 'ещё нет' }}
                @if (row.nextCheckAt) { · Следующая проверка: {{ row.nextCheckAt | date:'dd.MM.yyyy HH:mm' }} }</small>
            </div>
            @if (row.outcome !== 'ARCHIVED') { <button type="button" [disabled]="busy()" (click)="confirm.set(row)">Проверить сейчас №{{ row.orderId }}</button> }
          </article>
        } @empty { <p>Нет заказов, ожидающих этой проверки.</p> }
      </div>
      @if (confirm(); as row) {
        <div class="veil"><section role="dialog" aria-modal="true" aria-labelledby="archive-check-title">
          <h3 id="archive-check-title">Проверить заказ №{{ row.orderId }}?</h3>
          <p>Система прочитает доступную переписку и проверит условия. При полной истории, отсутствии ответов и оплаты заказ будет перенесён в архив. При недоступных данных проверка будет отложена.</p>
          <button type="button" (click)="confirm.set(null)">Отмена</button>
          <button type="button" (click)="check(row)">Проверить условия и применить</button>
        </section></div>
      }
    </section>`,
  styles: [`
    :host{display:block;margin:1rem 0}.archive-checks{border:1px solid #ffffff25;border-radius:16px;padding:1rem;background:#ffffff05}
    .heading,article{display:flex;justify-content:space-between;align-items:flex-start;gap:1rem}h3{margin:0}p{margin:.5rem 0;line-height:1.5}
    .rows{max-height:38rem;overflow:auto}article{padding:1rem 0;border-top:1px solid #ffffff20}small{opacity:.75}
    button{color:inherit;background:#ffffff12;border:1px solid #ffffff30;border-radius:8px;padding:.7rem;cursor:pointer;flex-shrink:0}button:disabled{opacity:.5}
    .veil{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;background:#000a;padding:1rem}.veil section{max-width:36rem;background:#252b33;padding:1.5rem;border-radius:16px}.veil button{margin:.5rem .5rem 0 0}
    @media(max-width:700px){.heading,article{flex-direction:column}}
  `]
})
export class ReviewArchiveChecksComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly url = `${appEnvironment.apiBaseUrl}/api/admin/client-messages/archive-checks`;
  readonly rows = signal<ArchiveCheck[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly confirm = signal<ArchiveCheck | null>(null);
  ngOnInit(): void { this.load(); }
  load(): void {
    this.busy.set(true); this.error.set(null);
    this.http.get<ArchiveCheck[]>(this.url).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: rows => this.rows.set(rows), error: error => this.error.set(apiErrorMessage(error, 'Не удалось загрузить проверки автоархива.'))
    });
  }
  check(row: ArchiveCheck): void {
    this.confirm.set(null); this.busy.set(true); this.error.set(null);
    this.http.post<{archived: boolean; reason: string}>(`${this.url}/${row.stateId}/check`, {}).subscribe({
      next: result => { this.message.set(result.reason); this.load(); },
      error: error => { this.busy.set(false); this.error.set(apiErrorMessage(error, 'Проверка не выполнена.')); }
    });
  }
  label(outcome: string | null): string {
    return ({ ARCHIVED: 'Архивирован', REPLY_FOUND: 'Есть ответ', HISTORY_MISSING: 'Нужен источник истории',
      HISTORY_UNAVAILABLE: 'Ожидает доступную историю', MISSING_BINDING: 'Нужна привязка чата', STATE_CHANGED: 'Условия изменились' } as Record<string,string>)[outcome || ''] || 'Ожидает проверки';
  }
}
