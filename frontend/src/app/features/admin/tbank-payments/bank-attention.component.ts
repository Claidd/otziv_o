import { Component, EventEmitter, OnDestroy, Output, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { PaymentsApi, type BankAttentionItem } from '../../../core/payments.api';
import { apiErrorDetail } from '../../../shared/api-error-message';

const COLLAPSED_COUNT = 5;

/** «Требует решения»: every payment matter waiting for a person, oldest first, at the top of «Банк». */
@Component({
  selector: 'app-bank-attention',
  templateUrl: './bank-attention.component.html',
  styleUrl: './bank-attention.component.scss'
})
export class BankAttentionComponent implements OnDestroy {
  private readonly api = inject(PaymentsApi);
  private read?: Subscription;
  private destroyed = false;

  @Output() readonly showInJournal = new EventEmitter<number>();
  @Output() readonly showReviews = new EventEmitter<void>();

  readonly items = signal<BankAttentionItem[]>([]);
  readonly truncated = signal(false);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly expanded = signal(false);
  readonly visible = computed(() => this.expanded() ? this.items() : this.items().slice(0, COLLAPSED_COUNT));
  readonly hidden = computed(() => Math.max(0, this.items().length - COLLAPSED_COUNT));

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
      error: err => {
        this.error.set(apiErrorDetail(err, 'Не удалось загрузить список «Требует решения».'));
        this.loading.set(false);
      }
    });
  }

  toggle(): void {
    this.expanded.update(value => !value);
  }

  kindIcon(item: BankAttentionItem): string {
    switch (item.kind) {
      case 'OWNER_APPROVAL': return 'how_to_reg';
      case 'SPECIALIST_REVIEW': return 'rule';
      case 'LINK_RECONCILIATION': return 'sync_problem';
      default: return 'mark_email_unread';
    }
  }

  waiting(item: BankAttentionItem): string {
    if (!item.since) return 'срок неизвестен';
    const since = new Date(item.since).getTime();
    if (!Number.isFinite(since)) return 'срок неизвестен';
    const days = Math.floor((Date.now() - since) / 86_400_000);
    if (days <= 0) return 'с сегодня';
    return `ждёт ${days} дн.`;
  }

  overdue(item: BankAttentionItem): boolean {
    if (!item.since) return false;
    const since = new Date(item.since).getTime();
    return Number.isFinite(since) && Date.now() - since >= 3 * 86_400_000;
  }

  formatMoney(kopecks: number | null): string {
    if (kopecks == null) return '—';
    return `${new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(kopecks / 100)} ₽`;
  }

  formatDate(value: string | null): string {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'
    }).format(date);
  }

  trackItem(item: BankAttentionItem): string {
    return `${item.kind}:${item.approvalId ?? item.reviewId ?? item.paymentLinkId ?? item.orderId}`;
  }
}
