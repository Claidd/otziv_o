import { Component, EventEmitter, OnDestroy, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { manualPaymentReviewPresentation } from '@otziv/client-common/billing-payments';
import { firstValueFrom, Subscription } from 'rxjs';
import {
  PaymentAdministrationApi,
  type SpecialistPaymentReview
} from '../core/payment-administration.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';

type ReviewAction = 'received' | 'absent';

@Component({
  selector: 'app-specialist-payment-reviews',
  imports: [FormsModule],
  templateUrl: './specialist-payment-reviews.component.html',
  styleUrl: './specialist-payment-reviews.component.scss'
})
export class SpecialistPaymentReviewsComponent implements OnDestroy {
  private readonly api = inject(PaymentAdministrationApi);
  private readonly confirm = inject(MobileConfirmService);
  private read?: Subscription;
  private destroyed = false;

  readonly reviews = signal<SpecialistPaymentReview[]>([]);
  @Output() readonly reviewsChanged = new EventEmitter<SpecialistPaymentReview[]>();
  @Output() readonly paymentChanged = new EventEmitter<void>();
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly busyId = signal<number | null>(null);
  readonly editing = signal<{ id: number; action: ReviewAction } | null>(null);
  readonly amount = signal('');
  readonly note = signal('');
  readonly statementChecked = signal(false);
  readonly formError = signal<string | null>(null);

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
    this.read = this.api.getSpecialistPaymentReviews().subscribe({
      next: reviews => {
        this.reviews.set(reviews ?? []);
        this.reviewsChanged.emit(reviews ?? []);
        this.loading.set(false);
      },
      error: error => {
        this.error.set(this.errorMessage(error, 'Не удалось загрузить ручные оплаты на контроле.'));
        this.loading.set(false);
      }
    });
  }

  statusLabel(review: SpecialistPaymentReview): string {
    return manualPaymentReviewPresentation(review).title;
  }

  canRetry(review: SpecialistPaymentReview): boolean {
    return review.retryNotificationAllowed === true;
  }

  canVerify(review: SpecialistPaymentReview): boolean {
    return ['WAITING', 'DISPUTED', 'NEEDS_ATTENTION'].includes(review.status);
  }

  canMarkAbsent(review: SpecialistPaymentReview): boolean {
    return ['PENDING_DELIVERY', 'WAITING', 'DISPUTED', 'NEEDS_ATTENTION'].includes(review.status);
  }

  money(kopecks: number): string {
    return `${new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(kopecks / 100)} ₽`;
  }

  date(value: string | null): string {
    return value ? value.replace('T', ' ').slice(0, 16) : 'нет';
  }

  open(review: SpecialistPaymentReview, action: ReviewAction): void {
    this.editing.set({ id: review.id, action });
    this.amount.set('');
    this.note.set('');
    this.statementChecked.set(false);
    this.formError.set(null);
  }

  close(): void {
    if (this.busyId() !== null) return;
    this.editing.set(null);
    this.formError.set(null);
  }

  async submit(review: SpecialistPaymentReview): Promise<void> {
    const editing = this.editing();
    if (!editing || editing.id !== review.id || this.busyId() !== null) return;
    const note = this.note().trim();
    if (!this.statementChecked() || !note || note.length > 500) {
      this.formError.set('Проверьте выписку получателя и укажите основание сверки (до 500 символов).');
      return;
    }
    let receivedAmountKopecks: number | null = null;
    if (editing.action === 'received') {
      receivedAmountKopecks = this.parseMoney(this.amount());
      if (receivedAmountKopecks !== review.amountKopecks) {
        this.formError.set(`Введите точную сумму поступления: ${this.money(review.amountKopecks)}.`);
        return;
      }
    }
    const confirmed = await this.confirm.confirm({
      title: editing.action === 'received' ? 'Подтвердить поступление' : 'Зарегистрировать отсутствие платежа',
      message: editing.action === 'received'
        ? `Выписка получателя подтверждает ${this.money(review.amountKopecks)} по заказу №${review.orderId}? Будет сохранён результат сверки.`
        : `По выписке получателя перевод ${this.money(review.amountKopecks)} по заказу №${review.orderId} не поступил? ${review.creditedAt ? 'Будет зарегистрировано возражение. Отметка оплаты не отменяется автоматически.' : 'Проверка будет закрыта без зачисления.'}`,
      confirmText: 'Сохранить сверку',
      danger: editing.action === 'absent'
    });
    if (!confirmed || this.destroyed) return;
    const request = editing.action === 'received'
      ? this.api.verifySpecialistPaymentReceived(review.id, {
        recipientStatementChecked: true, paymentReceived: true,
        receivedAmountKopecks: receivedAmountKopecks!, note
      })
      : this.api.markSpecialistPaymentAbsent(review.id, {
        recipientStatementChecked: true, paymentAbsent: true, note
      });
    await this.run(review.id, request, true);
  }

  async retry(review: SpecialistPaymentReview): Promise<void> {
    if (!this.canRetry(review) || this.busyId() !== null) return;
    await this.run(review.id, this.api.retrySpecialistPaymentNotification(review.id));
  }

  async reconcile(review: SpecialistPaymentReview): Promise<void> {
    if (this.busyId() !== null) return;
    await this.run(review.id, this.api.reconcileSpecialistPaymentReview(review.id));
  }

  private async run(
    id: number,
    request: ReturnType<PaymentAdministrationApi['reconcileSpecialistPaymentReview']>,
    closeForm = false
  ): Promise<void> {
    this.busyId.set(id);
    this.error.set(null);
    this.formError.set(null);
    try {
      await firstValueFrom(request);
      if (closeForm) this.editing.set(null);
      this.paymentChanged.emit();
      this.load();
    } catch (error) {
      const message = this.errorMessage(error, 'Не удалось завершить проверку. Обновите список и проверьте состояние платежа.');
      if (closeForm) this.formError.set(message);
      else this.error.set(message);
    } finally {
      this.busyId.set(null);
    }
  }

  private parseMoney(value: string): number | null {
    const normalized = value.replace(/\s/g, '').replace(',', '.');
    if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) return null;
    const [rubles, kopecks = ''] = normalized.split('.');
    const result = Number(rubles) * 100 + Number(kopecks.padEnd(2, '0'));
    return Number.isSafeInteger(result) && result > 0 ? result : null;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const payload = error.error;
      if (payload && typeof payload === 'object') {
        for (const key of ['detail', 'message', 'error']) {
          const detail = (payload as Record<string, unknown>)[key];
          if (typeof detail === 'string' && detail.trim()) return detail;
        }
      }
    }
    return fallback;
  }
}
