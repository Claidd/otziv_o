import { Component, Input, OnChanges, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  type ManualPaymentReviewView, manualPaymentReviewPresentation,
  manualPaymentReviewCanDispute, manualPaymentReviewDisputeRequest
} from '@otziv/client-common/billing-payments';
import { AuthService } from '../core/auth.service';
import { ManualPaymentReviewApi } from '../core/manual-payment-review.api';

@Component({
  selector: 'app-manual-payment-review',
  imports: [FormsModule],
  templateUrl: './manual-payment-review.component.html',
  styleUrl: './manual-payment-review.component.scss'
})
export class ManualPaymentReviewComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) orderId!: number;
  @Input() refreshKey: unknown;
  private readonly api = inject(ManualPaymentReviewApi);
  private readonly auth = inject(AuthService);
  private read?: Subscription;
  private write?: Subscription;
  private generation = 0;
  private activeOrderId: number | null = null;
  readonly review = signal<ManualPaymentReviewView | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly editing = signal(false);
  readonly checked = signal(false);
  readonly note = signal('');
  readonly presentation = computed(() => {
    const review = this.review();
    return review ? manualPaymentReviewPresentation(review) : null;
  });
  readonly canDispute = computed(() => manualPaymentReviewCanDispute(this.review()));

  ngOnChanges(): void {
    if (this.activeOrderId === this.orderId) {
      if (!this.saving()) this.load();
      return;
    }
    this.activeOrderId = this.orderId;
    this.generation++;
    this.read?.unsubscribe();
    this.write?.unsubscribe();
    this.review.set(null);
    this.editing.set(false);
    this.checked.set(false);
    this.note.set('');
    this.saving.set(false);
    this.error.set(null);
    this.loading.set(false);
    if (this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER'])
        && Number.isSafeInteger(this.orderId) && this.orderId > 0) this.load();
  }

  ngOnDestroy(): void {
    this.generation++;
    this.read?.unsubscribe();
    this.write?.unsubscribe();
  }

  load(): void {
    if (this.saving() || !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER'])
        || !Number.isSafeInteger(this.orderId) || this.orderId <= 0) return;
    this.read?.unsubscribe();
    const generation = ++this.generation;
    const orderId = this.orderId;
    this.loading.set(true);
    this.error.set(null);
    this.read = this.api.get(orderId).subscribe({
      next: review => {
        if (generation !== this.generation) return;
        this.loading.set(false);
        if (review && review.orderId !== orderId) {
          this.review.set(null);
          this.error.set('Получено состояние другого заказа. Обновите страницу.');
          return;
        }
        this.review.set(review);
        if (!manualPaymentReviewCanDispute(review)) this.editing.set(false);
      },
      error: () => {
        if (generation !== this.generation) return;
        this.loading.set(false);
        this.review.set(null);
        this.error.set('Не удалось проверить отметку оплаты. Обновите состояние.');
      }
    });
  }

  openDispute(): void {
    if (!this.canDispute() || this.loading() || this.saving()) return;
    this.editing.set(true);
    this.checked.set(false);
    this.note.set('');
    this.error.set(null);
  }

  submitDispute(): void {
    if (this.loading() || this.saving()) return;
    const review = this.review();
    const request = manualPaymentReviewDisputeRequest(review, this.checked(), this.note());
    if (!review || !request) {
      this.error.set('Подтвердите обращение специалиста или получателя и укажите его содержание (10–500 символов).');
      return;
    }
    const generation = this.generation;
    const orderId = this.orderId;
    this.saving.set(true);
    this.error.set(null);
    this.write = this.api.dispute(orderId, { ...request, expectedReviewId: review.id }).subscribe({
      next: updated => {
        if (generation !== this.generation) return;
        this.saving.set(false);
        if (updated.orderId !== orderId || updated.id !== review.id) {
          this.review.set(null);
          this.error.set('Состояние оплаты изменилось. Обновите его перед дальнейшими действиями.');
          return;
        }
        this.review.set(updated);
        this.editing.set(false);
        this.note.set('');
        this.checked.set(false);
      },
      error: () => {
        if (generation !== this.generation) return;
        this.saving.set(false);
        this.review.set(null);
        this.editing.set(false);
        this.error.set('Не удалось подтвердить сохранение возражения. Обновите состояние перед повтором.');
      }
    });
  }
}
