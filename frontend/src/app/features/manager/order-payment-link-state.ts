import { computed, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import type { ManagerPaymentLinkResponse, PaymentsApi, TbankPaymentStatus } from '../../core/payments.api';
import { apiErrorMessage } from '../../shared/api-error-message';

export interface OrderPaymentLinkEffects {
  started(): void;
  ready(text: string, created: boolean, isCurrent: () => boolean): void;
  failed(message: string): void;
}

/** One order-view instance owns its status read, link cache and mutations.
 * Navigation invalidates UI effects; an already-issued write is never replayed.
 * Clipboard/toast rendering remains in the component through the small effects port. */
export class OrderPaymentLinkState {
  readonly status = signal<TbankPaymentStatus | null>(null);
  readonly link = signal<ManagerPaymentLinkResponse | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly modeLabel = computed(() => {
    const status = this.status();
    if (!status) return 'Проверка';
    if (status.runtimeMode === 'TEST') return 'Тестовый режим';
    return status.applyConfirmedPayments ? 'Автоучёт оплаты' : 'Без автоучёта';
  });
  private readonly subscriptions = new Subscription();
  private generation = 0;
  private disposed = false;
  private statusRequested = false;

  constructor(private readonly api: Pick<PaymentsApi, 'getTbankStatus' | 'createOrderPaymentLink'>,
    private readonly effects: OrderPaymentLinkEffects) {}

  initialize(canManagePayments: boolean): void {
    // ADMIN/OWNER endpoint: avoid a guaranteed 403 for other board roles.
    if (!canManagePayments || this.disposed || this.statusRequested) return;
    this.statusRequested = true;
    this.subscriptions.add(this.api.getTbankStatus().subscribe({
      next: status => { if (!this.disposed) this.status.set(status); },
      error: () => { if (!this.disposed) this.status.set(null); }
    }));
  }

  resetRoute(): void {
    this.generation++;
    this.link.set(null); this.busy.set(false); this.error.set(null);
  }

  createOrCopy(orderId: number | null, permitted: boolean): void {
    if (!orderId || !permitted || this.disposed || this.busy()) return;
    const existing = this.link();
    const text = existing?.copyText || existing?.url;
    const generation = this.generation;
    if (text) { this.effects.ready(text, false, () => this.current(generation)); return; }
    this.busy.set(true); this.error.set(null); this.effects.started();
    this.subscriptions.add(this.api.createOrderPaymentLink(orderId).subscribe({
      next: response => {
        if (!this.current(generation)) return;
        this.link.set(response); this.busy.set(false);
        this.effects.ready(response.copyText || response.url, true, () => this.current(generation));
      },
      error: error => {
        if (!this.current(generation)) return;
        const message = apiErrorMessage(error, 'Не удалось создать ссылку на оплату');
        this.busy.set(false); this.error.set(message); this.effects.failed(message);
      }
    }));
  }

  private current(generation: number): boolean { return !this.disposed && generation === this.generation; }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true; this.resetRoute(); this.subscriptions.unsubscribe();
  }
}
