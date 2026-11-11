import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, signal } from '@angular/core';
import type {
  CommonInvoiceReturnableOrder,
  CommonInvoiceReturnableOrdersResponse
} from '../../../core/common-billing.api';

/** The sentence every refusal of the server starts with; the window shows it once, in bold. */
export const RETURN_ORDER_REFUSAL_LEAD = 'Заказ не возвращён, счёт не изменён.';

/**
 * The «Вернуть заказ в счёт» window of a common invoice that is still being collected: orders of
 * the invoice's own link that go separately now. A row is returned only after its consequence is
 * confirmed in the window; a refusal stays in the window with what to do next.
 */
@Component({
  selector: 'app-common-invoice-return-orders',
  template: `
<div class="ret-backdrop" role="presentation" (click)="close()">
  <section class="ret-modal" role="dialog" aria-modal="true" aria-labelledby="return-orders-title"
    (click)="$event.stopPropagation()" (keydown.escape)="close()">
    <header class="ret-head">
      <div>
        @if (heading) { <span class="ret-kicker">{{ heading }}</span> }
        <h2 id="return-orders-title">Вернуть заказ в счёт</h2>
        <p>Заказы компаний этой связи, которые сейчас идут отдельно от общего счёта.</p>
      </div>
      <button type="button" class="ret-close" aria-label="Закрыть" [disabled]="saving" (click)="close()">
        <span class="material-icons-sharp" aria-hidden="true">close</span>
      </button>
    </header>

    @if (loading) {
      <p class="ret-state" role="status">Загружаем заказы…</p>
    } @else if (error) {
      <p class="ret-error" role="alert">{{ error }}</p>
      <button type="button" class="ret-link" (click)="retry.emit()">Повторить</button>
    } @else if (orders && !orders.canReturn) {
      <div class="ret-error" role="alert">
        <strong>Вернуть заказ в этот счёт нельзя, счёт не изменён.</strong>
        <span>{{ orders.blockReason || 'Состав счёта уже зафиксирован. Что делать: ведите заказ отдельно — он оплачивается своим счётом.' }}</span>
      </div>
    } @else if (orders && !rows.length) {
      <div class="ret-empty" role="status">
        <strong>Возвращать нечего</strong>
        <span>У компаний этой связи нет заказов вне общего счёта: из счёта ничего не исключали, либо исключённый заказ уже
          завершён, оплачен, в архиве, в бане или «Не оплачено» — такие заказы в счёт не возвращаются.</span>
        <span>Вернуть можно незавершённый заказ в статусах от «Новый» до «Напоминание».</span>
      </div>
    } @else if (orders) {
      <ul class="ret-list" aria-label="Заказы, которые можно вернуть в счёт">
        @for (order of rows; track order.orderId) {
          <li class="ret-row" [class.confirming]="confirming() === order.orderId">
            <div class="ret-main">
              <span class="ret-text">
                <strong>{{ order.companyTitle || 'Компания №' + order.companyId }}</strong>
                <small>{{ order.orderTitle || 'Заказ №' + order.orderId }}</small>
              </span>
              <span class="ret-tag">{{ order.status }}</span>
              <strong class="ret-amount">{{ order.amountKopecks > 0 ? money(order.amountKopecks) : 'сумма не посчитана' }}</strong>
              @if (confirming() !== order.orderId) {
                <button type="button" class="ret-button" [disabled]="saving"
                  [attr.aria-label]="'Вернуть заказ №' + order.orderId + ' в счёт'" (click)="ask(order)">Вернуть</button>
              }
            </div>
            @if (confirming() === order.orderId) {
              <div class="ret-confirm" role="group" [attr.aria-label]="'Подтверждение возврата заказа №' + order.orderId">
                <p>{{ consequence(order) }}</p>
                <div class="ret-confirm-actions">
                  <button type="button" class="ret-button" [disabled]="saving" (click)="cancel()">Отмена</button>
                  <button type="button" class="ret-button ret-button--primary" [disabled]="saving" (click)="confirm(order)">
                    {{ saving ? 'Возвращаем…' : 'Подтвердить возврат' }}
                  </button>
                </div>
              </div>
            }
          </li>
        }
      </ul>
    }

    @if (refusal) {
      <div class="ret-error ret-refusal" role="alert">
        <strong>{{ refusalLead }}</strong>
        <span>{{ refusalText }}</span>
        @if (!refusal.includes('Что делать')) {
          <span>Что делать: обновите страницу и повторите возврат; если отказ повторится, ведите заказ отдельно.</span>
        }
      </div>
    }

    <div class="ret-actions">
      <button type="button" class="ret-button" [disabled]="saving" (click)="close()">Закрыть</button>
    </div>
  </section>
</div>
  `,
  styles: [`
:host {
  position: fixed;
  inset: 0;
  z-index: 1200;
  --ret-surface: #ffffff;
  --ret-soft: #f5f7fa;
  --ret-border: #e3e8ef;
  --ret-control-border: #d5dde7;
  --ret-text: #1f2430;
  --ret-muted: #67758a;
  --ret-accent: #a93422;
  --ret-accent-border: #e4a493;
  --ret-success: #126650;
  --ret-success-soft: #e8f7f2;
  --ret-success-border: #a7d9ca;
  --ret-danger: #a62b48;
  --ret-danger-soft: #fff0f3;
  --ret-danger-border: #e7a5b5;
  --ret-disabled-text: #929ba8;
}
:host-context(body.otziv-dark-theme) {
  --ret-surface: #22272b;
  --ret-soft: #2a3137;
  --ret-border: #3b444c;
  --ret-control-border: #46515a;
  --ret-text: #f2f5f8;
  --ret-muted: #aeb8c3;
  --ret-accent: #ffad95;
  --ret-accent-border: #765044;
  --ret-success: #79d8c2;
  --ret-success-soft: #203a34;
  --ret-success-border: #386c61;
  --ret-danger: #ff9ab6;
  --ret-danger-soft: #402630;
  --ret-danger-border: #754354;
  --ret-disabled-text: #707b85;
}
* { box-sizing: border-box; }
.ret-backdrop {
  position: fixed;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: rgba(12, 16, 22, 0.62);
  overflow-y: auto;
}
.ret-modal {
  width: min(100%, 38rem);
  max-height: calc(100vh - 2rem);
  overflow-y: auto;
  padding: 1.1rem 1.15rem 1.15rem;
  color: var(--ret-text);
  background: var(--ret-surface);
  border: 1px solid var(--ret-border);
  border-radius: 18px;
  box-shadow: 0 24px 60px rgba(8, 12, 18, 0.35);
  scrollbar-width: thin;
  scrollbar-color: var(--ret-control-border) transparent;
}
.ret-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.8rem;
}
.ret-kicker {
  display: block;
  font-size: 0.66rem;
  font-weight: 900;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ret-accent);
}
.ret-head h2 { margin: 0.1rem 0 0; font-size: 1.15rem; line-height: 1.25; }
.ret-head p { margin: 0.2rem 0 0; font-size: 0.8rem; line-height: 1.4; color: var(--ret-muted); }
.ret-modal .ret-close {
  display: grid;
  place-items: center;
  flex: none;
  width: 2.1rem;
  height: 2.1rem;
  min-height: 0;
  padding: 0;
  color: var(--ret-text);
  background: var(--ret-soft);
  border: 1px solid var(--ret-border);
  border-radius: 999px;
  box-shadow: none;
  cursor: pointer;
}
.ret-modal .ret-close:not(:disabled):hover { color: var(--ret-text); background: var(--ret-soft); border-color: var(--ret-accent-border); }
.ret-list { display: grid; gap: 0.4rem; margin: 0; padding: 0; list-style: none; }
.ret-row {
  display: grid;
  gap: 0.55rem;
  padding: 0.6rem 0.75rem;
  background: var(--ret-soft);
  border: 1px solid var(--ret-border);
  border-radius: 12px;
}
.ret-row.confirming { background: var(--ret-success-soft); border-color: var(--ret-success-border); }
.ret-main { display: flex; align-items: center; flex-wrap: wrap; gap: 0.5rem 0.65rem; min-width: 0; }
.ret-text { display: grid; gap: 0.12rem; min-width: 8rem; flex: 1; }
.ret-text strong { font-size: 0.9rem; overflow-wrap: anywhere; }
.ret-text small { font-size: 0.75rem; line-height: 1.35; color: var(--ret-muted); overflow-wrap: anywhere; }
.ret-tag {
  flex: none;
  padding: 0.15rem 0.5rem;
  border-radius: 999px;
  font-size: 0.66rem;
  font-weight: 800;
  white-space: nowrap;
  color: var(--ret-muted);
  background: var(--ret-surface);
  border: 1px solid var(--ret-border);
}
.ret-amount { flex: none; font-size: 0.88rem; white-space: nowrap; }
.ret-confirm { display: grid; gap: 0.55rem; }
.ret-confirm p { margin: 0; font-size: 0.8rem; line-height: 1.4; }
.ret-confirm-actions, .ret-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 0.6rem; }
.ret-actions { margin-top: 1rem; }
.ret-state, .ret-error, .ret-empty { margin: 0; font-size: 0.8rem; line-height: 1.4; }
.ret-state { color: var(--ret-muted); }
.ret-error {
  padding: 0.55rem 0.7rem;
  color: var(--ret-danger);
  background: var(--ret-danger-soft);
  border: 1px solid var(--ret-danger-border);
  border-radius: 10px;
}
div.ret-error, .ret-empty { display: grid; gap: 0.25rem; }
.ret-refusal { margin-top: 0.7rem; }
.ret-empty {
  padding: 0.6rem 0.75rem;
  color: var(--ret-muted);
  background: var(--ret-soft);
  border: 1px solid var(--ret-border);
  border-radius: 12px;
}
.ret-empty strong { color: var(--ret-text); font-size: 0.88rem; }
.ret-modal .ret-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  height: 2.3rem;
  min-height: 0;
  padding: 0 1rem;
  color: var(--ret-text);
  background: var(--ret-surface);
  border: 1px solid var(--ret-control-border);
  border-radius: 12px;
  box-shadow: none;
  font-weight: 700;
  cursor: pointer;
}
.ret-modal .ret-button:not(:disabled):hover { color: var(--ret-text); background: var(--ret-surface); border-color: var(--ret-accent-border); }
.ret-modal .ret-button--primary { color: var(--ret-success); background: var(--ret-success-soft); border-color: var(--ret-success-border); }
.ret-modal .ret-button--primary:not(:disabled):hover { color: var(--ret-success); background: var(--ret-success-soft); border-color: var(--ret-success); }
.ret-modal .ret-button:disabled { color: var(--ret-disabled-text); cursor: not-allowed; border-style: dashed; }
.ret-modal .ret-link {
  display: inline;
  height: auto;
  min-height: 0;
  margin-top: 0.4rem;
  padding: 0;
  color: var(--ret-accent);
  background: transparent;
  border: 0;
  box-shadow: none;
  font-size: 0.8rem;
  font-weight: 700;
  text-decoration: underline;
  cursor: pointer;
}
.ret-modal .ret-link:not(:disabled):hover { color: var(--ret-accent); background: transparent; }
.ret-modal .ret-button:focus-visible, .ret-modal .ret-close:focus-visible, .ret-modal .ret-link:focus-visible { outline: 2px solid var(--ret-accent); outline-offset: 2px; }
  `]
})
export class CommonInvoiceReturnOrdersComponent implements OnChanges {
  @Input() invoiceId = 0;
  @Input() heading = '';
  /** The server's answer; null until it is loaded. */
  @Input() orders: CommonInvoiceReturnableOrdersResponse | null = null;
  @Input() loading = false;
  @Input() error = '';
  @Input() saving = false;
  /** The server's refusal of the last return; the window stays open with it. */
  @Input() refusal = '';

  @Output() readonly returned = new EventEmitter<CommonInvoiceReturnableOrder>();
  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly retry = new EventEmitter<void>();

  /** The order whose return waits for the manager's confirmation. */
  readonly confirming = signal<number | null>(null);
  readonly refusalLead = RETURN_ORDER_REFUSAL_LEAD;

  get rows(): CommonInvoiceReturnableOrder[] {
    return this.orders?.orders ?? [];
  }

  /** The refusal without the opening sentence that the window already shows in bold. */
  get refusalText(): string {
    const text = this.refusal.trim();
    return text.startsWith(RETURN_ORDER_REFUSAL_LEAD) ? text.slice(RETURN_ORDER_REFUSAL_LEAD.length).trim() : text;
  }

  ngOnChanges(changes: SimpleChanges): void {
    // A refused return is over: the row goes back to its «Вернуть» button under the refusal.
    if (changes['refusal']?.currentValue) {
      this.confirming.set(null);
    }
  }

  /** What the confirmation will do, said before the button is pressed. */
  consequence(order: CommonInvoiceReturnableOrder): string {
    const amount = order.amountKopecks > 0
      ? `сумма счёта вырастет на ${this.money(order.amountKopecks)}`
      : 'сумма счёта будет пересчитана';
    return `Заказ №${order.orderId} вернётся в общий счёт №${this.invoiceId}: ${amount}. `
      + 'Отдельный счёт заказа закроется, если по нему ещё не платили; если оплата уже начата, заказ не вернётся и ничего не изменится. '
      + 'Клиенту сейчас ничего не отправляется — заказ уйдёт ему в составе общего счёта.';
  }

  ask(order: CommonInvoiceReturnableOrder): void {
    if (!this.saving) {
      this.confirming.set(order.orderId);
    }
  }

  cancel(): void {
    if (!this.saving) {
      this.confirming.set(null);
    }
  }

  confirm(order: CommonInvoiceReturnableOrder): void {
    if (this.saving || this.confirming() !== order.orderId) {
      return;
    }
    this.returned.emit(order);
  }

  close(): void {
    if (!this.saving) {
      this.closed.emit();
    }
  }

  money(kopecks: number | null | undefined): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format((kopecks ?? 0) / 100)} ₽`;
  }
}
