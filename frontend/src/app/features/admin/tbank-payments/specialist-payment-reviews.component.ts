import { Component, EventEmitter, OnDestroy, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  PaymentsApi,
  type SpecialistPaymentReview,
  type SpecialistPaymentReviewStatus
} from '../../../core/payments.api';
import { apiErrorDetail } from '../../../shared/api-error-message';

type ReviewAction = 'received' | 'absent';

@Component({
  selector: 'app-specialist-payment-reviews',
  imports: [FormsModule],
  templateUrl: './specialist-payment-reviews.component.html',
  styleUrl: './specialist-payment-reviews.component.scss'
})
export class SpecialistPaymentReviewsComponent implements OnDestroy {
  private readonly api = inject(PaymentsApi);
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
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить проверки переводов специалистам.'));
        this.loading.set(false);
      }
    });
  }

  statusLabel(status: SpecialistPaymentReviewStatus): string {
    const labels: Record<SpecialistPaymentReviewStatus, string> = {
      PENDING_DELIVERY: 'Уведомление не доставлено',
      WAITING: 'Ожидаем возражения специалиста',
      SETTLING: 'Идёт зачисление',
      ADMIN_SETTLING: 'Идёт ручная сверка',
      DISPUTED: 'Специалист оспорил перевод',
      CONFIRMED: 'Зачислено',
      NEEDS_ATTENTION: 'Требуется проверка',
      SUPERSEDED: 'Закрыто без поступления'
    };
    return labels[status] ?? status;
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

  formatMoney(kopecks: number): string {
    return `${new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(kopecks / 100)} ₽`;
  }

  formatDate(value: string | null): string {
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

  submit(review: SpecialistPaymentReview): void {
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
        this.formError.set(`Введите точную сумму поступления: ${this.formatMoney(review.amountKopecks)}.`);
        return;
      }
    }
    const confirmation = editing.action === 'received'
      ? `Выписка специалиста подтверждает поступление ${this.formatMoney(review.amountKopecks)} по заказу №${review.orderId}? После сохранения заказ будет зачислен.`
      : `По выписке специалиста перевод ${this.formatMoney(review.amountKopecks)} по заказу №${review.orderId} не поступил? Запрос будет закрыт без зачисления.`;
    if (!window.confirm(confirmation)) return;
    this.formError.set(null);
    const request = editing.action === 'received'
      ? this.api.verifySpecialistPaymentReceived(review.id, {
        recipientStatementChecked: true, paymentReceived: true,
        receivedAmountKopecks: receivedAmountKopecks!, note
      })
      : this.api.markSpecialistPaymentAbsent(review.id, {
        recipientStatementChecked: true, paymentAbsent: true, note
      });
    this.run(review.id, request, true);
  }

  retry(review: SpecialistPaymentReview): void {
    if (!this.canRetry(review) || this.busyId() !== null) return;
    this.run(review.id, this.api.retrySpecialistPaymentNotification(review.id));
  }

  reconcile(review: SpecialistPaymentReview): void {
    if (this.busyId() !== null) return;
    this.run(review.id, this.api.reconcileSpecialistPaymentReview(review.id));
  }

  private run(id: number, request: ReturnType<PaymentsApi['reconcileSpecialistPaymentReview']>, closeForm = false): void {
    this.busyId.set(id);
    this.error.set(null);
    request.subscribe({
      next: () => {
        this.busyId.set(null);
        if (closeForm) this.editing.set(null);
        this.paymentChanged.emit();
        this.load();
      },
      error: error => {
        this.busyId.set(null);
        const message = apiErrorDetail(error, 'Не удалось завершить проверку. Обновите список и проверьте состояние платежа.');
        if (closeForm) this.formError.set(message);
        else this.error.set(message);
      }
    });
  }

  private parseMoney(value: string): number | null {
    const normalized = value.replace(/\s/g, '').replace(',', '.');
    if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) return null;
    const [rubles, kopecks = ''] = normalized.split('.');
    const result = Number(rubles) * 100 + Number(kopecks.padEnd(2, '0'));
    return Number.isSafeInteger(result) && result > 0 ? result : null;
  }
}
