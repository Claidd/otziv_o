import { Component, DestroyRef, HostListener, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PaymentsApi, PublicCommonInvoice } from '../../core/payments.api';
import { LatestRouteRequest } from '../../core/latest-route-request';
import { RouteEpoch, RouteEpochTicket } from '../../core/route-epoch';
import { apiErrorMessage } from '../../shared/api-error-message';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { navigateToPaymentTarget } from '../../shared/payment-navigation';

@Component({
  selector: 'app-pay-group-page',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
  templateUrl: './pay-group-page.component.html',
  styleUrl: './pay-group-page.component.scss'
})
export class PayGroupPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly paymentsApi = inject(PaymentsApi);
  private readonly invoiceRouteRequest = new LatestRouteRequest<PublicCommonInvoice>();
  private readonly routeEpoch = new RouteEpoch();

  readonly token = signal('');
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
  readonly paymentRouteType = computed(() => (this.invoice()?.paymentRouteType ?? '').trim().toUpperCase());
  readonly tbankRoute = computed(() => this.paymentRouteType() === 'TBANK_LINK');
  readonly managerTextRoute = computed(() => this.paymentRouteType() === 'MANAGER_TEXT');
  readonly manualRoute = computed(() => this.paymentRouteType() === 'MANUAL_MOBILE_BANK'
    || this.paymentRouteType() === 'MANUAL_EXTERNAL_LINK');
  readonly externalManualRoute = computed(() => this.paymentRouteType() === 'MANUAL_EXTERNAL_LINK'
    || this.invoice()?.manualPaymentType === 'EXTERNAL_LINK');
  readonly manualPaymentUrl = computed(() => this.invoice()?.manualPaymentUrl?.trim() ?? '');
  readonly manualPaymentButtonLabel = computed(() => this.invoice()?.manualPaymentButtonLabel?.trim()
    || 'Открыть ссылку оплаты');
  readonly manualComment = computed(() => this.invoice()?.manualComment?.trim() || 'Оплата общего счета');
  readonly routeTitle = computed(() => {
    if (this.managerTextRoute()) {
      return 'Реквизиты менеджера';
    }
    if (this.externalManualRoute()) {
      return 'Оплата по ссылке банка';
    }
    if (this.manualRoute()) {
      return 'Оплата по номеру телефона';
    }
    return 'Оплата остатка';
  });
  readonly canSubmit = computed(() => {
    const email = this.email().trim();
    return Boolean(
      this.invoice()?.payable &&
      this.tbankRoute() &&
      email &&
      email.includes('@') &&
      this.offerConsent() &&
      this.privacyConsent() &&
      this.receiptConsent() &&
      !this.submitting()
    );
  });

  async copyPaymentValue(value?: string | null): Promise<void> {
    const clean = value?.trim();
    if (!clean) {
      return;
    }
    const routeTicket = this.captureRoute();
    if (!routeTicket) {
      return;
    }
    const copied = await copyTextToClipboard(clean);
    if (this.isActiveRoute(routeTicket)) {
      this.message.set(copied ? 'Скопировано.' : 'Не получилось скопировать. Выделите текст вручную.');
    }
  }

  openManualPaymentUrl(): void {
    const routeTicket = this.captureRoute();
    const url = this.manualPaymentUrl();
    if (!routeTicket || !url) {
      return;
    }
    if (!this.navigatePayment(url, routeTicket)) {
      this.error.set('Ссылка оплаты имеет недопустимый формат.');
    }
  }

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.routeEpoch.destroy();
      this.invoiceRouteRequest.cancel();
    });
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.activateInvoiceRoute(params.get('token')));
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

    const routeTicket = this.captureRoute();
    if (!routeTicket) {
      return;
    }
    const token = this.token();

    this.submitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicCommonInvoicePayment(
      token,
      this.email().trim(),
      this.offerConsent(),
      this.privacyConsent(),
      this.receiptConsent()
    ).subscribe({
      next: (response) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        if (response.paymentUrl) {
          if (this.navigatePayment(response.paymentUrl, routeTicket)) {
            return;
          }
          this.error.set('Банк вернул недопустимую ссылку оплаты. Переход отменен.');
          this.submitting.set(false);
          return;
        }
        this.message.set('Банк не вернул ссылку на оплату. Попробуйте еще раз позже.');
        this.submitting.set(false);
      },
      error: (err) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
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
    const token = this.token();
    const routeTicket = this.captureRoute();
    if (!token || !routeTicket) {
      this.loading.set(false);
      this.error.set('Общая платежная ссылка не найдена.');
      return;
    }

    this.loading.set(true);
    this.invoiceRouteRequest.start(this.paymentsApi.getPublicCommonInvoice(token), {
      next: (invoice) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.applyInvoice(invoice);
        this.loading.set(false);
      },
      error: (err) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
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
    const routeTicket = this.captureRoute();
    const token = this.token();
    if (!routeTicket || !token) {
      this.refreshing.set(false);
      return;
    }
    this.invoiceRouteRequest.start(this.paymentsApi.getPublicCommonInvoice(token), {
      next: (invoice) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.applyInvoice(invoice);
        this.refreshing.set(false);
      },
      error: () => {
        if (this.isActiveRoute(routeTicket)) {
          this.refreshing.set(false);
        }
      }
    });
  }

  private applyInvoice(invoice: PublicCommonInvoice): void {
    this.invoice.set(invoice);
    this.submitting.set(false);
  }

  private activateInvoiceRoute(rawToken: string | null): void {
    const token = rawToken?.trim() ?? '';
    const routeKey = token ? `pay-group:${token}` : 'pay-group:invalid';
    if (!this.routeEpoch.change(routeKey)) {
      return;
    }

    this.invoiceRouteRequest.cancel();
    this.clearRouteState();
    this.token.set(token);
    if (!token) {
      this.error.set('Общая платежная ссылка не найдена.');
      return;
    }
    this.loadInvoice();
  }

  private clearRouteState(): void {
    this.invoice.set(null);
    this.loading.set(false);
    this.refreshing.set(false);
    this.submitting.set(false);
    this.error.set('');
    this.message.set('');
    this.email.set('');
    this.offerConsent.set(false);
    this.privacyConsent.set(false);
    this.receiptConsent.set(false);
    this.lastReturnRefreshAt = 0;
  }

  private captureRoute(): RouteEpochTicket | null {
    return this.routeEpoch.capture();
  }

  private isActiveRoute(ticket: RouteEpochTicket): boolean {
    return this.routeEpoch.accepts(ticket);
  }

  private navigatePayment(value: unknown, routeTicket: RouteEpochTicket): boolean {
    return this.isActiveRoute(routeTicket)
      && navigateToPaymentTarget(value, 'payment', (target) => window.location.assign(target));
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
