import { Component, EventEmitter, OnDestroy, Output, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { PaymentAdministrationApi, type BankAttentionItem } from '../core/payment-administration.api';

/** «Требует решения» in the app: collapsed to one line, so the payment journal below stays reachable. */
@Component({
  selector: 'app-bank-attention',
  templateUrl: './bank-attention.component.html',
  styleUrl: './bank-attention.component.scss'
})
export class BankAttentionComponent implements OnDestroy {
  private readonly api = inject(PaymentAdministrationApi);
  private read?: Subscription;
  private destroyed = false;

  @Output() readonly showInJournal = new EventEmitter<number>();

  readonly items = signal<BankAttentionItem[]>([]);
  readonly truncated = signal(false);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly expanded = signal(false);
  readonly oldest = computed(() => this.items()[0] ?? null);

  constructor() { this.load(); }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.read?.unsubscribe();
  }

  load(): void {
    if (this.destroyed) return;
    this.read?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.read = this.api.getBankAttention().subscribe({
      next: response => {
        this.items.set(response?.items ?? []);
        this.truncated.set(response?.truncated === true);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Не удалось загрузить список «Требует решения». Потяните страницу вниз, чтобы повторить.');
        this.loading.set(false);
      }
    });
  }

  toggle(): void {
    this.expanded.update(value => !value);
  }

  open(item: BankAttentionItem): void {
    if (item.orderId) {
      this.expanded.set(false);
      this.showInJournal.emit(item.orderId);
    }
  }

  waiting(item: BankAttentionItem | null): string {
    if (!item?.since) return '';
    const since = new Date(item.since).getTime();
    if (!Number.isFinite(since)) return '';
    const days = Math.floor((Date.now() - since) / 86_400_000);
    return days <= 0 ? 'с сегодня' : `ждёт ${days} дн.`;
  }

  overdue(item: BankAttentionItem): boolean {
    if (!item.since) return false;
    const since = new Date(item.since).getTime();
    return Number.isFinite(since) && Date.now() - since >= 3 * 86_400_000;
  }

  money(kopecks: number | null): string {
    if (kopecks == null) return '—';
    return `${new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(kopecks / 100)} ₽`;
  }

  date(value: string | null): string {
    if (!value) return '—';
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return '—';
    return new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'
    }).format(parsed);
  }

  trackItem(item: BankAttentionItem): string {
    return `${item.kind}:${item.approvalId ?? item.reviewId ?? item.paymentLinkId ?? item.orderId}`;
  }
}
