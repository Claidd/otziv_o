import { Component, ElementRef, OnInit, afterRenderEffect, inject, signal, viewChild } from '@angular/core';
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
      <div class="ac-head">
        <div class="ac-head-copy">
          <h3>Проверка автоархива</h3>
          <p>Заказы, ожидающие тексты клиента или согласование: после семи доставленных напоминаний система проверяет переписку, оплату и срок ожидания. После закрытия последнего заказа компания переходит на стоп и в очередь предложений. Время — иркутское.</p>
        </div>
        <button type="button" class="ac-button" (click)="refresh()" [disabled]="busy()">
          <span class="material-icons-sharp" aria-hidden="true">refresh</span>Обновить проверки
        </button>
      </div>
      @if (error()) { <p class="ac-note ac-note--error" role="alert">{{ error() }}</p> }
      @if (message()) { <p class="ac-note" role="status">{{ message() }}</p> }
      <div class="ac-rows">
        @for (row of rows(); track row.stateId) {
          <article class="ac-row">
            <div class="ac-row-copy">
              <strong>Заказ №{{ row.orderId }} · {{ row.company }}</strong>
              <p class="ac-row-meta">{{ row.channel || 'Канал ещё не проверен' }} · <span class="ac-state" [attr.data-outcome]="row.outcome">{{ label(row.outcome) }}</span></p>
              <p>{{ row.reason || (row.outcome === 'ARCHIVED' ? 'Заказ перенесён в архив; уведомления сохранены в очереди.' : 'Ожидает автоматической проверки.') }}</p>
              <small>Проверено: {{ row.checkedAt ? (row.checkedAt | date:'dd.MM.yyyy HH:mm:ss') : 'ещё нет' }}
                @if (row.nextCheckAt) { · Следующая проверка: {{ row.nextCheckAt | date:'dd.MM.yyyy HH:mm' }} }</small>
            </div>
            @if (row.outcome !== 'ARCHIVED') {
              <button type="button" class="ac-button ac-button--check" [disabled]="busy()" (click)="confirm.set(row)">
                <span class="material-icons-sharp" aria-hidden="true">fact_check</span>Проверить сейчас №{{ row.orderId }}
              </button>
            }
          </article>
        } @empty { <p class="ac-empty">Нет заказов, ожидающих этой проверки.</p> }
      </div>
    </section>
    @if (confirm(); as row) {
      <div class="ac-veil">
        <section class="ac-dialog" role="dialog" tabindex="-1" aria-modal="true" aria-labelledby="archive-check-title"
          aria-describedby="archive-check-text" (keydown.escape)="confirm.set(null)">
          <header class="ac-dialog-head">
            <span class="ac-dialog-tile material-icons-sharp" aria-hidden="true">archive</span>
            <div class="ac-dialog-title">
              <span class="ac-kicker">Автоархив</span>
              <h3 id="archive-check-title">Проверить заказ №{{ row.orderId }}?</h3>
              <p>{{ row.company }}</p>
            </div>
            <button type="button" class="ac-close" aria-label="Закрыть" title="Закрыть" (click)="confirm.set(null)">
              <span class="material-icons-sharp" aria-hidden="true">close</span>
            </button>
          </header>
          <p class="ac-dialog-text" id="archive-check-text">
            <span class="material-icons-sharp" aria-hidden="true">info</span>
            <span>Система прочитает доступную переписку и проверит условия. При полной истории, без ответов клиента и без оплаты заказ уйдёт в «Архив»: клиенту и сотрудникам будут отправлены уведомления, а компании может быть запланировано предложение нового заказа. Если данных не хватает, проверка будет отложена.</span>
          </p>
          <div class="ac-dialog-actions">
            <button #cancelButton type="button" class="ac-button" (click)="confirm.set(null)">Отмена</button>
            <button type="button" class="ac-button ac-button--primary" (click)="check(row)">Проверить условия и применить</button>
          </div>
        </section>
      </div>
    }`,
  styles: [`
    @use '../../../shared/plain-modal' as plain;

    // The autoarchive check of the «Мониторинг» tab: a block in the page font on the soft site surface, rows
    // divided by neutral lines, a neutral «Обновить проверки» and a calm «Проверить сейчас». The confirmation
    // is a window of the accepted look (shared/plain-modal.scss). The buttons repeat their class to outweigh
    // the global .admin-layout button rules of styles.scss.
    :host {
      @include plain.tokens;
      display: block;
      min-width: 0;
    }

    :host-context(body.otziv-dark-theme) {
      @include plain.dark-tokens;
    }

    .archive-checks {
      min-width: 0;
      border: 1px solid var(--otziv-surface-border-neutral);
      border-radius: 1rem;
      padding: 1rem 1.1rem 0.35rem;
      color: var(--otziv-dark);
      background: var(--otziv-surface-gradient-neutral);
      box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
      font-family: var(--otziv-font-family);
    }

    .ac-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    .ac-head-copy {
      min-width: 0;
    }

    .ac-head h3 {
      margin: 0;
      color: var(--otziv-dark);
      font-size: 1rem;
      font-weight: 800;
      line-height: 1.25;
    }

    .ac-head p {
      max-width: 62rem;
      margin: 0.3rem 0 0;
      color: var(--otziv-info);
      font-size: 0.84rem;
      line-height: 1.45;
    }

    .archive-checks .ac-button.ac-button,
    .ac-dialog .ac-button.ac-button {
      @include plain.button;
      flex: none;
      white-space: nowrap;
    }

    .archive-checks .ac-button.ac-button {
      min-height: 2.35rem;
      padding: 0 0.9rem;
      font-size: 0.8rem;
    }

    .ac-note {
      margin: 0.75rem 0 0;
      border: 1px solid var(--otziv-tone-walk-border);
      border-radius: 10px;
      padding: 0.55rem 0.7rem;
      color: var(--otziv-dark);
      background: var(--otziv-tone-walk-surface);
      font-size: 0.82rem;
      line-height: 1.45;
      overflow-wrap: anywhere;
    }

    .ac-note--error {
      border-color: var(--otziv-tone-bad-border);
      border-left: 3px solid var(--otziv-danger);
      background: var(--otziv-tone-bad-surface);
    }

    .ac-rows {
      max-height: 38rem;
      max-height: min(38rem, 72dvh);
      margin-top: 0.85rem;
      overflow: auto;
      border-top: 1px solid var(--otziv-surface-border-neutral);
      padding-right: 0.3rem;
      scrollbar-width: thin;
      scrollbar-color: var(--plain-control-border) transparent;
    }

    .ac-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: start;
      gap: 0.5rem 1rem;
      padding: 0.85rem 0;
    }

    .ac-row + .ac-row {
      border-top: 1px solid var(--otziv-surface-border-neutral);
    }

    .ac-row-copy {
      min-width: 0;
    }

    .ac-row-copy > strong {
      display: block;
      color: var(--otziv-dark);
      font-size: 0.92rem;
      font-weight: 800;
      line-height: 1.3;
      overflow-wrap: anywhere;
    }

    .ac-row-copy p {
      margin: 0.3rem 0 0;
      color: var(--otziv-dark);
      font-size: 0.84rem;
      line-height: 1.45;
      overflow-wrap: anywhere;
    }

    .ac-row-copy .ac-row-meta {
      color: var(--otziv-info);
      font-size: 0.74rem;
      font-weight: 800;
      letter-spacing: 0.02em;
    }

    // The result of the last check as a small status mark: green — archived, amber — the history or the chat
    // binding is missing, neutral — everything else.
    .ac-state {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      border: 1px solid var(--otziv-tone-walk-border);
      border-radius: 999px;
      padding: 0.05rem 0.5rem;
      color: var(--otziv-dark);
      background: var(--otziv-tone-walk-surface);
      font-size: 0.72rem;
      font-weight: 700;
      letter-spacing: 0;
      line-height: 1.35;
    }

    .ac-state::before {
      content: '';
      flex: none;
      width: 0.45rem;
      height: 0.45rem;
      border-radius: 50%;
      background: var(--otziv-info);
    }

    .ac-state[data-outcome='ARCHIVED'] {
      border-color: var(--otziv-tone-success-border);
      background: var(--otziv-tone-success-surface);
    }

    .ac-state[data-outcome='ARCHIVED']::before {
      background: var(--otziv-success);
    }

    .ac-state[data-outcome='HISTORY_MISSING'],
    .ac-state[data-outcome='HISTORY_UNAVAILABLE'],
    .ac-state[data-outcome='MISSING_BINDING'] {
      border-color: var(--otziv-tone-wait-border);
      background: var(--otziv-tone-wait-surface);
    }

    .ac-state[data-outcome='HISTORY_MISSING']::before,
    .ac-state[data-outcome='HISTORY_UNAVAILABLE']::before,
    .ac-state[data-outcome='MISSING_BINDING']::before {
      background: var(--otziv-warning);
    }

    .ac-row-copy small {
      display: block;
      margin-top: 0.35rem;
      color: var(--otziv-info);
      font-size: 0.74rem;
      line-height: 1.4;
    }

    .ac-empty {
      margin: 0;
      padding: 0.9rem 0 0.6rem;
      color: var(--otziv-info);
      font-size: 0.84rem;
    }

    // The confirmation window. A click on the dimmed backdrop does nothing: only «Отмена», the cross and Esc close it.
    .ac-veil {
      position: fixed;
      inset: 0;
      z-index: 1200;
      display: grid;
      place-items: center;
      overflow-y: auto;
      padding: 1rem;
      background: rgba(12, 16, 22, 0.62);
    }

    .ac-dialog:focus {
      outline: none;
    }

    .ac-dialog {
      width: min(100%, 32rem);
      max-height: calc(100vh - 2rem);
      max-height: calc(100dvh - 2rem);
      overflow-y: auto;
      border: 1px solid var(--plain-border);
      border-radius: 18px;
      color: var(--plain-text);
      background: var(--plain-surface);
      box-shadow: 0 24px 60px rgba(8, 12, 18, 0.35);
      font-family: var(--otziv-font-family);
      scrollbar-width: thin;
      scrollbar-color: var(--plain-control-border) transparent;
    }

    .ac-dialog-head {
      @include plain.head-band;
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 1rem 1.15rem 0.9rem;
    }

    .ac-dialog-tile {
      @include plain.icon-tile;
      flex: none;
    }

    .ac-dialog-title {
      flex: 1 1 auto;
      min-width: 0;
    }

    .ac-kicker {
      display: block;
      color: var(--otziv-action);
      font-size: 0.66rem;
      font-weight: 900;
      letter-spacing: 0.06em;
      line-height: 1.3;
      text-transform: uppercase;
    }

    .ac-dialog-title h3 {
      margin: 0.1rem 0 0;
      color: var(--plain-text);
      font-size: 1.15rem;
      font-weight: 800;
      line-height: 1.25;
      overflow-wrap: anywhere;
    }

    .ac-dialog-title p {
      margin: 0.15rem 0 0;
      color: var(--plain-muted);
      font-size: 0.8rem;
      line-height: 1.35;
      overflow-wrap: anywhere;
    }

    .ac-dialog .ac-close.ac-close {
      display: grid;
      flex: none;
      width: 2.1rem;
      height: 2.1rem;
      min-height: 0;
      place-items: center;
      border: 1px solid var(--plain-control-border);
      border-radius: 999px;
      padding: 0;
      color: var(--plain-text);
      background: var(--plain-surface);
      box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);
      cursor: pointer;

      &:not(:disabled):hover {
        border-color: var(--otziv-action-border);
        color: var(--otziv-action);
        background: var(--plain-surface);
      }

      &:focus-visible {
        outline: 2px solid var(--otziv-action);
        outline-offset: 2px;
      }

      .material-icons-sharp {
        font-size: 1.25rem;
      }
    }

    .ac-dialog-text {
      display: flex;
      align-items: flex-start;
      gap: 0.55rem;
      margin: 1rem 1.15rem 0;
      border: 1px solid var(--plain-border);
      border-radius: 12px;
      padding: 0.7rem 0.8rem;
      color: var(--plain-text);
      background: var(--plain-soft);
      font-size: 0.86rem;
      line-height: 1.5;
    }

    .ac-dialog-text .material-icons-sharp {
      flex: none;
      color: var(--otziv-action);
      font-size: 1.15rem;
      line-height: 1.3;
    }

    .ac-dialog-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 0.6rem;
      padding: 1rem 1.15rem 1.15rem;
    }

    .ac-dialog .ac-button.ac-button--primary.ac-button--primary {
      @include plain.button-save;
    }

    @media (max-width: 700px) {
      .archive-checks {
        padding: 0.9rem 0.9rem 0.3rem;
      }

      .ac-head {
        flex-direction: column;
        align-items: stretch;
        gap: 0.75rem;
      }

      .ac-head > .ac-button,
      .ac-row > .ac-button {
        align-self: flex-start;
        justify-self: start;
      }

      .ac-row {
        grid-template-columns: minmax(0, 1fr);
        gap: 0.65rem;
      }
    }

    @media (max-width: 480px) {
      .ac-veil {
        padding: 0.75rem;
      }

      .ac-dialog {
        max-height: calc(100vh - 1.5rem);
        max-height: calc(100dvh - 1.5rem);
      }

      .ac-dialog-head {
        padding: 0.9rem 1rem 0.8rem;
      }

      .ac-dialog-title h3 {
        font-size: 1.05rem;
      }

      .ac-dialog-text {
        margin: 0.9rem 1rem 0;
      }

      .ac-dialog-actions {
        display: grid;
        grid-template-columns: minmax(0, 1fr);
        padding: 0.9rem 1rem 1rem;
      }

      // On the narrowest phones the long «Проверить условия и применить» wraps instead of overflowing its button.
      .ac-dialog .ac-button.ac-button {
        min-height: 2.75rem;
        padding: 0.45rem 1rem;
        line-height: 1.25;
        text-align: center;
        white-space: normal;
      }
    }
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
  private readonly cancelButton = viewChild<ElementRef<HTMLButtonElement>>('cancelButton');
  private returnFocus: HTMLElement | null = null;

  constructor() {
    // The confirmation takes the keyboard focus on «Отмена» and gives it back to the row button when it closes.
    afterRenderEffect(() => {
      const cancel = this.cancelButton()?.nativeElement;
      if (cancel) {
        const active = document.activeElement;
        this.returnFocus = active instanceof HTMLElement && active !== document.body ? active : null;
        cancel.focus();
        return;
      }
      const target = this.returnFocus;
      this.returnFocus = null;
      if (target?.isConnected) target.focus({ preventScroll: true });
    });
  }
  ngOnInit(): void { this.load(); }
  /** «Обновить проверки»: the result line of an earlier check goes away with the old list. */
  refresh(): void {
    this.message.set(null);
    this.load();
  }
  load(): void {
    this.busy.set(true); this.error.set(null);
    this.http.get<ArchiveCheck[]>(this.url).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: rows => this.rows.set(rows), error: error => this.error.set(apiErrorMessage(error, 'Не удалось загрузить проверки автоархива.'))
    });
  }
  check(row: ArchiveCheck): void {
    this.confirm.set(null); this.busy.set(true); this.error.set(null); this.message.set(null);
    this.http.post<{archived: boolean; reason: string | null}>(`${this.url}/${row.stateId}/check`, {}).subscribe({
      // The line names the order: the list is reloaded right after and may no longer show the checked row.
      next: result => { this.message.set(this.checkResult(row, result)); this.load(); },
      error: error => { this.busy.set(false); this.error.set(apiErrorMessage(error, 'Проверка не выполнена.')); }
    });
  }
  private checkResult(row: ArchiveCheck, result: { archived: boolean; reason: string | null }): string {
    const reason = result.reason?.trim() || (result.archived ? 'заказ перенесён в архив.' : 'заказ не перенесён в архив.');
    return `Заказ №${row.orderId}: ${reason}`;
  }
  label(outcome: string | null): string {
    return ({ ARCHIVED: 'Архивирован', REPLY_FOUND: 'Есть ответ', HISTORY_MISSING: 'Нужен источник истории',
      HISTORY_UNAVAILABLE: 'Ожидает доступную историю', MISSING_BINDING: 'Нужна привязка чата', STATE_CHANGED: 'Условия изменились' } as Record<string,string>)[outcome || ''] || 'Ожидает проверки';
  }
}
