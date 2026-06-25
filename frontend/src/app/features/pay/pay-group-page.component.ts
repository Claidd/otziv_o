import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PaymentsApi, PublicCommonInvoice } from '../../core/payments.api';
import { apiErrorMessage } from '../../shared/api-error-message';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';

@Component({
  selector: 'app-pay-group-page',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
  templateUrl: './pay-group-page.component.html',
  styleUrl: './pay-group-page.component.scss'
})
export class PayGroupPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly paymentsApi = inject(PaymentsApi);

  readonly token = signal(this.route.snapshot.paramMap.get('token') ?? '');
  readonly invoice = signal<PublicCommonInvoice | null>(null);
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly submitting = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly email = signal('');
  readonly offerConsent = signal(false);
  readonly privacyConsent = signal(false);
  readonly receiptConsent = signal(false);

  private lastReturnRefreshAt = 0;

  readonly title = computed(() => this.invoice()?.payable ? 'Общий счет' : 'Общий счет');
  readonly statusLabel = computed(() => this.statusText(this.invoice()?.status));
  readonly paidOrders = computed(() => this.invoice()?.orders.filter((order) => order.paid).length ?? 0);
  readonly readyOrders = computed(() => this.invoice()?.orders.filter((order) => order.ready).length ?? 0);
  readonly canSubmit = computed(() => {
    const email = this.email().trim();
    return Boolean(
      this.invoice()?.payable &&
      email &&
      email.includes('@') &&
      this.offerConsent() &&
      this.privacyConsent() &&
      this.receiptConsent() &&
      !this.submitting()
    );
  });

  constructor() {
    this.loadInvoice();
  }

  @HostListener('window:pageshow')
  onPageShow(): void {
    this.refreshAfterReturn();
  }

  @HostListener('window:focus')
  onWindowFocus(): void {
    this.refreshAfterReturn();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (document.visibilityState === 'visible') {
      this.refreshAfterReturn();
    }
  }

  submitPayment(): void {
    if (!this.canSubmit()) {
      this.message.set('Укажите e-mail и подтвердите согласия.');
      return;
    }

    const bankWindow = this.openBlankBankWindow();
    this.submitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicCommonInvoicePayment(
      this.token(),
      this.email().trim(),
      this.offerConsent(),
      this.privacyConsent(),
      this.receiptConsent()
    ).subscribe({
      next: (response) => {
        if (response.paymentUrl) {
          if (bankWindow && !bankWindow.closed) {
            bankWindow.location.href = response.paymentUrl;
            this.message.set('Открыли форму банка в новой вкладке. После оплаты вернитесь сюда: остаток обновится автоматически.');
            this.submitting.set(false);
            return;
          }
          this.message.set('Браузер заблокировал новую вкладку, поэтому открываем форму банка здесь.');
          window.location.href = response.paymentUrl;
          return;
        }
        this.closeBankWindow(bankWindow);
        this.message.set('Банк не вернул ссылку на оплату. Попробуйте еще раз позже.');
        this.submitting.set(false);
      },
      error: (err) => {
        this.closeBankWindow(bankWindow);
        this.error.set(apiErrorMessage(err, 'Не удалось перейти к оплате.'));
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

  trackOrder(_index: number, order: { orderId: number }): number {
    return order.orderId;
  }

  private loadInvoice(): void {
    if (!this.token()) {
      this.loading.set(false);
      this.error.set('Общая платежная ссылка не найдена.');
      return;
    }

    this.paymentsApi.getPublicCommonInvoice(this.token()).subscribe({
      next: (invoice) => {
        this.applyInvoice(invoice);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось открыть общий счет.'));
        this.loading.set(false);
      }
    });
  }

  private refreshAfterReturn(): void {
    const now = Date.now();
    if (
      now - this.lastReturnRefreshAt < 1200 ||
      this.loading() ||
      this.refreshing() ||
      !this.token()
    ) {
      return;
    }

    const current = this.invoice();
    if (!current || current.status === 'PAID' || current.status === 'UNPAID' || current.status === 'DISABLED') {
      return;
    }

    this.lastReturnRefreshAt = now;
    this.refreshing.set(true);
    this.paymentsApi.getPublicCommonInvoice(this.token()).subscribe({
      next: (invoice) => {
        this.applyInvoice(invoice);
        this.refreshing.set(false);
      },
      error: () => this.refreshing.set(false)
    });
  }

  private applyInvoice(invoice: PublicCommonInvoice): void {
    this.invoice.set(invoice);
    this.submitting.set(false);
  }

  private openBlankBankWindow(): Window | null {
    try {
      const target = window.open('about:blank', '_blank');
      if (!target) {
        return null;
      }
      target.opener = null;
      target.document.title = 'Открываем банк';
      target.document.body.style.margin = '0';
      target.document.body.style.fontFamily = 'system-ui, sans-serif';
      target.document.body.style.display = 'grid';
      target.document.body.style.minHeight = '100vh';
      target.document.body.style.placeItems = 'center';
      target.document.body.textContent = 'Открываем форму банка...';
      return target;
    } catch {
      return null;
    }
  }

  private closeBankWindow(target: Window | null): void {
    try {
      if (target && !target.closed) {
        target.close();
      }
    } catch {
      // Browser access can be denied after a navigation attempt.
    }
  }

  private statusText(status?: string): string {
    switch (status) {
      case 'COLLECTING':
        return 'Собирается';
      case 'READY':
        return 'Готов к счету';
      case 'INVOICED':
        return 'Выставлен';
      case 'REMINDER':
        return 'Напоминание';
      case 'PARTIALLY_PAID':
        return 'Частично оплачен';
      case 'NEEDS_ATTENTION':
        return 'Требует проверки менеджером';
      case 'PAID':
        return 'Оплачен';
      case 'UNPAID':
        return 'Не оплачен';
      case 'BAN':
        return 'Бан';
      case 'DISABLED':
        return 'Отключен';
      default:
        return 'Проверяется';
    }
  }
}
