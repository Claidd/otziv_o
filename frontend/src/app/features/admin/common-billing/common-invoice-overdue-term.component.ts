import { Component, EventEmitter, Input, OnChanges, Output, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { apiErrorDetail } from '../../../shared/api-error-message';

export interface CommonInvoiceOverdueTerm {
  invoiceId: number;
  overdueDays: number;
  extensionDays: number;
  extensionBy: string | null;
  extensionAt: string | null;
  termStart: string | null;
  paperDelivery: boolean;
  automaticMoveNotBefore: string | null;
  editable: boolean;
  note: string;
}

export const MAX_OVERDUE_EXTENSION_DAYS = 60;

/**
 * The invoice's payment term before the automatic move to "Не оплачено" and the manager's
 * extension of it (for example one more week for a client who asked to pay later).
 */
@Component({
  selector: 'app-common-invoice-overdue-term',
  imports: [FormsModule],
  template: `
    @if (term(); as t) {
      <details class="overdue-term" [open]="opened" (toggle)="opened = $any($event.target).open">
        <summary>
          <span class="material-icons-sharp" aria-hidden="true">event</span>
          <div>
            <strong id="overdue-term-title">Срок оплаты</strong>
            <p>Переход в «Не оплачено», если клиент не оплатит вовремя.@if (t.extensionDays > 0) { Отсрочка {{ t.extensionDays }} дн.}</p>
          </div>
          <div class="overdue-term-tools">
            @if (t.automaticMoveNotBefore) {
              <small class="overdue-term-date" title="Раньше этой даты счёт не перейдёт в «Не оплачено»">
                не раньше {{ date(t.automaticMoveNotBefore) }}
              </small>
            }
            @if (help.observed) {
              <button type="button" class="overdue-term-help" (click)="$event.preventDefault(); $event.stopPropagation(); help.emit()"
                aria-label="Инструкция: срок оплаты" title="Подробнее об этом блоке">
                <span class="material-icons-sharp" aria-hidden="true">help_outline</span>
              </button>
            }
            <span class="material-icons-sharp overdue-term-fold" aria-hidden="true">expand_more</span>
          </div>
        </summary>
        <div class="overdue-term-body">
        <p>{{ t.note }}</p>
        @if (t.extensionDays > 0 && t.extensionBy) {
          <p class="overdue-term-hint">Отсрочка {{ t.extensionDays }} дн. задана: {{ t.extensionBy }}@if (t.extensionAt) {, {{ date(t.extensionAt) }}}</p>
        }
        @if (t.editable) {
          <form (ngSubmit)="save()">
            <label>
              <span>Отсрочка, дней</span>
              <input name="extensionDays" type="number" inputmode="numeric" min="0" [max]="maxDays" step="1"
                     [(ngModel)]="days" [disabled]="busy()" />
            </label>
            <button type="submit" [disabled]="busy() || !validDays() || days === t.extensionDays">
              {{ busy() ? 'Сохраняем…' : 'Сохранить' }}
            </button>
            <small>Клиент попросил оплатить позже — добавьте дни (до {{ maxDays }}). 0 — без отсрочки.</small>
          </form>
        }
        @if (error()) { <p role="alert" class="overdue-term-error">{{ error() }}</p> }
        </div>
      </details>
    }
  `,
  styles: [`
    .overdue-term{display:block;min-width:0;border:1px solid var(--billing-panel-border,#e3e8ef);border-radius:.7rem;background:var(--billing-summary-surface,#fff);color:var(--billing-action-text,inherit)}
    summary{display:grid;grid-template-columns:2rem minmax(0,1fr) auto;align-items:start;gap:.55rem;padding:.65rem;cursor:pointer;list-style:none}
    summary::-webkit-details-marker{display:none}
    summary:focus-visible{outline:0;border-radius:.7rem;box-shadow:0 0 0 3px color-mix(in srgb,var(--otziv-action,#e53220) 24%,transparent)}
    .overdue-term-body{display:grid;gap:.5rem;border-top:1px solid var(--billing-panel-border,#e3e8ef);padding:.55rem .65rem .65rem}
    .overdue-term-fold{align-self:center;color:var(--billing-action-muted,#596175);font-size:1.3rem;transition:transform 160ms ease}
    .overdue-term[open] .overdue-term-fold{transform:rotate(180deg)}
    summary>.material-icons-sharp{display:grid;width:2rem;height:2rem;place-items:center;border-radius:999px;color:var(--billing-action-warning-text,#805600);background:var(--billing-action-warning-soft,#fff6df);font-size:1.05rem}
    summary strong{display:block;font-size:.8rem;font-weight:950;line-height:1.2}
    summary p{margin-top:.12rem;color:var(--billing-action-muted,#596175)}
    .overdue-term-tools{display:flex;align-items:center;gap:.3rem}
    .overdue-term .overdue-term-help{display:grid;width:1.7rem;height:1.7rem;min-height:0;place-items:center;border:1px solid var(--billing-action-border,#cbd0da);border-radius:999px;padding:0;background:var(--billing-action-surface,#f5f7fa);color:var(--billing-action-muted,#596175);box-shadow:none;cursor:pointer}
    .overdue-term .overdue-term-help .material-icons-sharp{font-size:1rem}
    .overdue-term .overdue-term-help:not(:disabled):hover{border-color:var(--billing-action-primary-soft-border,#e4a493);color:var(--billing-action-primary-soft-text,#a93422);background:var(--billing-action-primary-soft,#fff2ed)}
    .overdue-term-date{border-radius:999px;padding:.2rem .5rem;color:var(--billing-action-warning-text,#805600);background:var(--billing-action-warning-soft,#fff6df);font-size:.64rem;font-weight:900;line-height:1.2;text-align:center;max-width:6.5rem}
    p{margin:0;font-size:.68rem;font-weight:750;line-height:1.38;overflow-wrap:anywhere}
    .overdue-term-hint{color:var(--billing-action-muted,#596175)}
    .overdue-term-error{color:var(--billing-alert-title,#c2334d)}
    form{display:grid;grid-template-columns:minmax(0,7rem) auto;align-items:end;gap:.45rem .5rem}
    form small{grid-column:1/-1;color:var(--billing-action-muted,#596175);font-size:.64rem;font-weight:750;line-height:1.3}
    label{display:grid;gap:.22rem;font-size:.64rem;font-weight:850;color:var(--billing-action-muted,#596175)}
    input{width:100%;min-height:2.2rem;padding:.3rem .5rem;border:1px solid var(--billing-action-border,#cbd0da);border-radius:.5rem;background:var(--billing-panel-surface,#fff);color:inherit;font:inherit;font-size:.8rem;font-weight:900}
    input:focus-visible{outline:3px solid color-mix(in srgb,var(--otziv-action,#e53220) 26%,transparent);outline-offset:1px}
    .overdue-term button{display:inline-flex;align-items:center;justify-content:center;min-height:2.2rem;padding:.3rem .9rem;border:1px solid var(--billing-action-primary-soft-border,#e4a493);border-radius:.55rem;background:var(--billing-action-primary-soft,#fff2ed);color:var(--billing-action-primary-soft-text,#a93422);box-shadow:none;font:inherit;font-size:.72rem;font-weight:900;cursor:pointer}
    .overdue-term button[type=submit]:not(:disabled):hover{border-color:var(--billing-action-primary-soft-border,#e4a493);color:var(--billing-action-primary-soft-text,#a93422);background:var(--billing-action-primary-soft-hover,#fbe2d9)}
    .overdue-term button:disabled{border-style:dashed;border-color:var(--billing-action-border,#cbd0da);background:var(--billing-action-disabled-bg,#edf0f4);color:var(--billing-action-disabled-text,#929ba8);cursor:not-allowed}
  `]
})
export class CommonInvoiceOverdueTermComponent implements OnChanges {
  @Input({ required: true }) invoiceId!: number;
  /** Changes when the invoice changes (status, sending, delivery), so the term is re-read. */
  @Input() revision: unknown;
  /** Opens the host's manual on this block; the «?» button appears only when someone listens. */
  @Output() readonly help = new EventEmitter<void>();
  private readonly http = inject(HttpClient);
  readonly term = signal<CommonInvoiceOverdueTerm | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly maxDays = MAX_OVERDUE_EXTENSION_DAYS;
  /** Rarely changed, so the block starts folded; the summary still shows the move date. */
  opened = false;
  days = 0;
  private ticket = 0;

  ngOnChanges(): void {
    this.load();
  }

  load(): void {
    if (!this.invoiceId) return;
    const ticket = ++this.ticket;
    this.http.get<CommonInvoiceOverdueTerm>(this.path()).subscribe({
      next: term => { if (ticket === this.ticket) { this.term.set(term); this.days = term.extensionDays; this.error.set(''); } },
      error: () => { if (ticket === this.ticket) this.term.set(null); }
    });
  }

  validDays(): boolean {
    return Number.isInteger(this.days) && this.days >= 0 && this.days <= MAX_OVERDUE_EXTENSION_DAYS;
  }

  save(): void {
    if (this.busy() || !this.validDays()) return;
    const ticket = ++this.ticket;
    this.busy.set(true); this.error.set('');
    this.http.put<CommonInvoiceOverdueTerm>(this.path(), { extensionDays: this.days }).subscribe({
      next: term => { if (ticket !== this.ticket) return; this.busy.set(false); this.term.set(term); this.days = term.extensionDays; },
      error: e => { if (ticket !== this.ticket) return; this.busy.set(false); this.error.set(apiErrorDetail(e, 'Не удалось сохранить отсрочку')); }
    });
  }

  date(value: string): string {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString('ru-RU');
  }

  private path(): string {
    return `/api/common-billing/invoices/${this.invoiceId}/overdue-term`;
  }
}
