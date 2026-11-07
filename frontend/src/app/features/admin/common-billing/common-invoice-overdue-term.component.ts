import { Component, Input, OnChanges, inject, signal } from '@angular/core';
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
      <section class="overdue-term" aria-label="Переход в «Не оплачено»">
        <header>
          <strong>Переход в «Не оплачено»</strong>
          @if (t.automaticMoveNotBefore) { <small>не раньше {{ date(t.automaticMoveNotBefore) }}</small> }
        </header>
        <p>{{ t.note }}</p>
        @if (t.extensionDays > 0 && t.extensionBy) {
          <p class="overdue-term-hint">Отсрочка {{ t.extensionDays }} дн. задана: {{ t.extensionBy }}@if (t.extensionAt) {, {{ date(t.extensionAt) }}}</p>
        }
        @if (t.editable) {
          <form (ngSubmit)="save()">
            <label>Отсрочка, дней
              <input name="extensionDays" type="number" inputmode="numeric" min="0" [max]="maxDays" step="1"
                     [(ngModel)]="days" [disabled]="busy()" />
            </label>
            <button type="submit" [disabled]="busy() || !validDays() || days === t.extensionDays">
              {{ busy() ? 'Сохраняем…' : 'Сохранить отсрочку' }}
            </button>
          </form>
        }
        @if (error()) { <p role="alert" class="overdue-term-error">{{ error() }}</p> }
      </section>
    }
  `,
  styles: [`
    .overdue-term{display:grid;gap:.45rem;border:1px solid var(--billing-action-border,#cbd0da);border-radius:.7rem;padding:.62rem;margin:.55rem 0;background:color-mix(in srgb,var(--billing-action-surface,#fff) 82%,transparent);color:var(--billing-action-text,inherit)}
    header{display:flex;align-items:baseline;justify-content:space-between;gap:.5rem;flex-wrap:wrap}
    header strong{font-size:.78rem;font-weight:950}
    header small{font-size:.7rem;font-weight:800}
    p{margin:0;font-size:.7rem;line-height:1.45;overflow-wrap:anywhere}
    .overdue-term-hint{color:var(--billing-action-muted,#596175)}
    .overdue-term-error{color:#c2334d}
    form{display:flex;align-items:flex-end;gap:.5rem;flex-wrap:wrap}
    label{display:grid;gap:.25rem;font-size:.66rem;font-weight:800;color:var(--billing-action-muted,#596175)}
    input{width:6rem;min-height:2.4rem;padding:.4rem .5rem;border:1px solid var(--billing-action-border,#cbd0da);border-radius:.5rem;background:transparent;color:inherit;font:inherit}
    button{min-height:2.4rem;padding:.4rem .8rem;border:1px solid var(--billing-action-border,#cbd0da);border-radius:.6rem;background:transparent;color:inherit;font:inherit;font-weight:800;cursor:pointer}
    button:disabled{opacity:.55;cursor:default}
  `]
})
export class CommonInvoiceOverdueTermComponent implements OnChanges {
  @Input({ required: true }) invoiceId!: number;
  /** Changes when the invoice changes (status, sending, delivery), so the term is re-read. */
  @Input() revision: unknown;
  private readonly http = inject(HttpClient);
  readonly term = signal<CommonInvoiceOverdueTerm | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly maxDays = MAX_OVERDUE_EXTENSION_DAYS;
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
