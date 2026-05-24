import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { apiErrorMessage } from '../../shared/api-error-message';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { PaymentsApi, PublicPaymentLink } from '../../core/payments.api';

@Component({
  selector: 'app-pay-page',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
  templateUrl: './pay-page.component.html',
  styleUrl: './pay-page.component.scss'
})
export class PayPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly paymentsApi = inject(PaymentsApi);

  readonly token = signal(this.route.snapshot.paramMap.get('token') ?? '');
  readonly payment = signal<PublicPaymentLink | null>(null);
  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly email = signal('');
  readonly offerConsent = signal(false);
  readonly receiptConsent = signal(false);

  readonly title = computed(() => {
    const payment = this.payment();
    return payment?.payable ? 'Оплата заказа' : 'Платежная ссылка';
  });

  readonly statusLabel = computed(() => this.statusText(this.payment()?.status));
  readonly canSubmit = computed(() => {
    const email = this.email().trim();
    return Boolean(
      this.payment()?.payable &&
      email &&
      email.includes('@') &&
      this.offerConsent() &&
      this.receiptConsent() &&
      !this.submitting()
    );
  });

  constructor() {
    this.loadPayment();
  }

  submit(): void {
    if (!this.canSubmit()) {
      this.message.set('Укажите e-mail и подтвердите согласия.');
      return;
    }

    this.submitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicPayment(this.token(), this.email().trim()).subscribe({
      next: (response) => {
        if (response.paymentUrl) {
          window.location.href = response.paymentUrl;
          return;
        }
        this.message.set('Банк не вернул ссылку на оплату. Попробуйте еще раз позже.');
        this.submitting.set(false);
      },
      error: (err) => {
        this.error.set(this.publicPaymentError(err));
        this.submitting.set(false);
      }
    });
  }

  formatRubles(amount?: number | null): string {
    return new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format(amount ?? 0);
  }

  private loadPayment(): void {
    if (!this.token()) {
      this.loading.set(false);
      this.error.set('Платежная ссылка не найдена.');
      return;
    }

    this.paymentsApi.getPublicPaymentLink(this.token()).subscribe({
      next: (payment) => {
        this.payment.set(payment);
        this.email.set(payment.payerEmail ?? '');
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось открыть платежную ссылку.'));
        this.loading.set(false);
      }
    });
  }

  private publicPaymentError(err: unknown): string {
    const message = apiErrorMessage(err, 'Не удалось перейти к оплате.');
    if (message.includes('Интернет-эквайринг Т-Банка выключен')) {
      return 'Тестовая оплата через Т-Банк пока выключена. Альфа-Банк остается рабочим способом оплаты.';
    }
    return message;
  }

  private statusText(status?: string): string {
    switch (status) {
      case 'CREATED':
        return 'Готова к оплате';
      case 'INITIATED':
      case 'AUTHORIZED':
        return 'Платеж начат';
      case 'CONFIRMED':
        return 'Оплачено';
      case 'TEST_CONFIRMED':
        return 'Тестовая оплата подтверждена';
      case 'EXPIRED':
        return 'Срок истек';
      case 'CANCELED':
      case 'REJECTED':
        return 'Недоступна';
      default:
        return 'Проверяется';
    }
  }
}
