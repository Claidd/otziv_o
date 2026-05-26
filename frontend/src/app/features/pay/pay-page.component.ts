import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { apiErrorMessage } from '../../shared/api-error-message';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { PaymentsApi, PublicPaymentLink, TbankPaymentPageMode } from '../../core/payments.api';

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
  readonly sbpSubmitting = signal(false);
  readonly bankSubmitting = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly email = signal('');
  readonly offerConsent = signal(false);
  readonly receiptConsent = signal(false);
  readonly sbpQrImage = signal('');
  readonly sbpQrPayload = signal('');
  readonly sbpPaymentUrl = signal('');

  readonly title = computed(() => {
    const payment = this.payment();
    return payment?.payable ? 'Оплата заказа' : 'Платежная ссылка';
  });

  readonly statusLabel = computed(() => this.statusText(this.payment()?.status));
  readonly paymentPageMode = computed<TbankPaymentPageMode>(() => this.payment()?.paymentPageMode ?? 'SBP_PRIMARY');
  readonly showSbpPayment = computed(() => this.paymentPageMode() !== 'BANK_ONLY');
  readonly showBankPayment = computed(() => this.paymentPageMode() !== 'SBP_ONLY');
  readonly bankFirst = computed(() => this.paymentPageMode() === 'BANK_PRIMARY' || this.paymentPageMode() === 'BANK_ONLY');
  readonly bankMethodChips = computed(() => {
    const payment = this.payment();
    const methods = ['Карта'];
    if (payment?.tpayEnabled) {
      methods.push('T-Pay');
    }
    if (payment?.sberpayEnabled) {
      methods.push('SberPay');
    }
    if (payment?.mirpayEnabled) {
      methods.push('Mir Pay');
    }
    return methods;
  });
  readonly bankPaymentTitle = computed(() => {
    const methods = this.bankMethodChips();
    if (methods.length === 1) {
      return this.showSbpPayment() ? 'Карта / другой способ' : 'Карта';
    }
    return this.showSbpPayment() ? `${methods.join(' / ')} / другой способ` : methods.join(' / ');
  });
  readonly paymentBadges = computed(() => {
    const payment = this.payment();
    const badges = ['МИР', 'VISA', 'Mastercard'];
    if (payment?.tpayEnabled) {
      badges.push('T-Pay');
    }
    if (payment?.sberpayEnabled) {
      badges.push('SberPay');
    }
    if (payment?.mirpayEnabled) {
      badges.push('Mir Pay');
    }
    if (this.showSbpPayment()) {
      badges.push('СБП');
    }
    badges.push('Без НДС');
    return badges;
  });
  readonly canSubmit = computed(() => {
    const email = this.email().trim();
    return Boolean(
      this.payment()?.payable &&
      email &&
      email.includes('@') &&
      this.offerConsent() &&
      this.receiptConsent() &&
      !this.sbpSubmitting() &&
      !this.bankSubmitting()
    );
  });
  readonly sbpQrImageSrc = computed(() => {
    const image = this.sbpQrImage().trim();
    if (!image) {
      return '';
    }
    if (image.startsWith('data:') || image.startsWith('http://') || image.startsWith('https://')) {
      return image;
    }
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(image)}`;
  });
  readonly hasSbpQr = computed(() => Boolean(this.sbpQrImageSrc() || this.sbpQrPayload()));

  constructor() {
    this.loadPayment();
  }

  submitSbp(): void {
    if (!this.canSubmit()) {
      this.message.set('Укажите e-mail и подтвердите согласия.');
      return;
    }

    this.sbpSubmitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicSbpPayment(
      this.token(),
      this.email().trim(),
      this.offerConsent(),
      this.offerConsent(),
      this.receiptConsent()
    ).subscribe({
      next: (response) => {
        this.sbpQrImage.set(response.qrImage ?? '');
        this.sbpQrPayload.set(response.qrPayload ?? '');
        this.sbpPaymentUrl.set(response.paymentUrl ?? '');
        if (response.qrImage || response.qrPayload) {
          this.message.set('QR-код СБП создан. Отсканируйте его в банковском приложении.');
          this.sbpSubmitting.set(false);
          return;
        }
        if (response.paymentUrl) {
          this.message.set('Банк не вернул QR-код. Можно открыть обычную форму оплаты.');
          this.sbpSubmitting.set(false);
          return;
        }
        this.message.set('Банк не вернул QR-код СБП. Попробуйте запасной способ оплаты.');
        this.sbpSubmitting.set(false);
      },
      error: (err) => {
        this.error.set(this.publicPaymentError(err));
        this.sbpSubmitting.set(false);
      }
    });
  }

  submitBankForm(): void {
    if (!this.canSubmit()) {
      this.message.set('Укажите e-mail и подтвердите согласия.');
      return;
    }

    this.bankSubmitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicPayment(
      this.token(),
      this.email().trim(),
      this.offerConsent(),
      this.offerConsent(),
      this.receiptConsent()
    ).subscribe({
      next: (response) => {
        if (response.paymentUrl) {
          window.location.href = response.paymentUrl;
          return;
        }
        this.message.set('Банк не вернул ссылку на оплату. Попробуйте еще раз позже.');
        this.bankSubmitting.set(false);
      },
      error: (err) => {
        this.error.set(this.publicPaymentError(err));
        this.bankSubmitting.set(false);
      }
    });
  }

  openSbpPayload(): void {
    const payload = this.sbpQrPayload().trim();
    if (!payload) {
      return;
    }
    window.location.href = payload;
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
