import { Component, DestroyRef, HostListener, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { apiErrorMessage } from '../../shared/api-error-message';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import {
  configuredPaymentTarget,
  navigateToPaymentTarget,
  type PaymentNavigationPurpose
} from '../../shared/payment-navigation';
import { PaymentsApi, PublicPaymentLink, PublicSbpBank, TbankPaymentPageMode } from '../../core/payments.api';
import { LatestRouteRequest } from '../../core/latest-route-request';
import { RouteEpoch, RouteEpochTicket } from '../../core/route-epoch';
import { manualTransferDestinationPresentation } from '../../shared/manual-transfer-destination';

@Component({
  selector: 'app-pay-page',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
  templateUrl: './pay-page.component.html',
  styleUrl: './pay-page.component.scss'
})
export class PayPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly paymentsApi = inject(PaymentsApi);
  private readonly paymentRouteRequest = new LatestRouteRequest<PublicPaymentLink>();
  private readonly sbpBanksRouteRequest = new LatestRouteRequest<PublicSbpBank[]>();
  private readonly routeEpoch = new RouteEpoch();

  readonly token = signal('');
  readonly payment = signal<PublicPaymentLink | null>(null);
  readonly loading = signal(true);
  readonly sbpSubmitting = signal(false);
  readonly bankSubmitting = signal(false);
  readonly manualSubmitting = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly email = signal('');
  readonly offerConsent = signal(false);
  readonly privacyConsent = signal(false);
  readonly receiptConsent = signal(false);
  readonly sbpPaymentPayload = signal('');
  readonly sbpPaymentUrl = signal('');
  readonly sbpBanks = signal<PublicSbpBank[]>([]);
  readonly sbpBanksLoading = signal(false);
  readonly sbpBanksError = signal('');
  readonly selectedSbpBankId = signal('');
  readonly refreshingPayment = signal(false);

  private lastReturnRefreshAt = 0;

  readonly title = computed(() => {
    const payment = this.payment();
    return payment?.payable ? 'Оплата заказа' : 'Платежная ссылка';
  });

  readonly manualPayment = computed(() => {
    const method = this.payment()?.paymentMethod;
    return method === 'MANUAL_MOBILE_BANK' || method === 'MANUAL_EXTERNAL_LINK';
  });
  readonly externalManualPayment = computed(() => {
    const payment = this.payment();
    return Boolean(payment)
      && (payment?.paymentMethod === 'MANUAL_EXTERNAL_LINK' || payment?.manualPaymentType === 'EXTERNAL_LINK');
  });
  readonly manualTransferDestination = computed(() => manualTransferDestinationPresentation(
    this.payment()?.manualPhone
  ));
  readonly manualPaymentTitle = computed(() => this.externalManualPayment()
    ? 'Оплата по ссылке банка'
    : this.manualTransferDestination().paymentTitle);
  readonly manualTransferDestinationLabel = computed(() => this.manualTransferDestination().fieldLabel);
  readonly statusLabel = computed(() => this.statusText(this.payment()?.status));
  readonly paymentPageMode = computed<TbankPaymentPageMode>(() => this.payment()?.paymentPageMode ?? 'SBP_PRIMARY');
  readonly showSbpPayment = computed(() => !this.manualPayment() && this.paymentPageMode() !== 'BANK_ONLY');
  readonly showBankPayment = computed(() => !this.manualPayment()
    && this.paymentPageMode() !== 'SBP_ONLY'
    && this.paymentPageMode() !== 'SBP_PAY_ONLY');
  readonly isPaymentComplete = computed(() => this.isCompletedStatus(this.payment()?.status));
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
  readonly fastBankMethods = computed(() => this.bankMethodChips().filter((method) => method !== 'Карта'));
  readonly hasFastBankMethods = computed(() => this.fastBankMethods().length > 0);
  readonly showFastBankMethods = computed(() => !this.manualPayment()
    && this.paymentPageMode() !== 'SBP_ONLY'
    && this.hasFastBankMethods());
  readonly checkoutTitle = computed(() => {
    if (this.showSbpPayment()) {
      return this.paymentPageMode() === 'SBP_PAY_ONLY' && this.hasFastBankMethods() ? 'СБП + Pay' : 'СБП';
    }
    return this.bankPaymentTitle();
  });
  readonly checkoutSubtitle = computed(() => this.showSbpPayment()
    ? 'Оплата через банковское приложение'
    : 'Оплата на защищенной странице банка'
  );
  readonly primaryButtonIcon = computed(() => {
    if (this.showSbpPayment()) {
      return this.sbpSubmitting() ? 'hourglass_top' : 'account_balance_wallet';
    }
    return this.bankSubmitting() ? 'hourglass_top' : 'payments';
  });
  readonly primaryButtonText = computed(() => {
    if (this.showSbpPayment()) {
      return this.sbpButtonText();
    }
    return this.bankSubmitting() ? 'Открываем форму банка' : 'Открыть форму банка';
  });
  readonly paymentCompleteTitle = computed(() => {
    return this.payment()?.status === 'TEST_CONFIRMED'
      ? 'Тестовая оплата подтверждена'
      : 'Оплата прошла успешно';
  });
  readonly paymentCompleteText = computed(() => {
    return this.payment()?.status === 'TEST_CONFIRMED'
      ? 'Банк подтвердил тестовый платеж. Повторная оплата по этой ссылке больше не нужна.'
      : 'Банк подтвердил платеж. Электронный чек будет отправлен на указанный e-mail.';
  });
  readonly paymentBadges = computed(() => {
    const payment = this.payment();
    const badges: string[] = [];
    if (this.showBankPayment()) {
      badges.push('МИР', 'VISA', 'Mastercard');
    }
    if (this.showBankPayment() || this.paymentPageMode() === 'SBP_PAY_ONLY') {
      if (payment?.tpayEnabled) {
        badges.push('T-Pay');
      }
      if (payment?.sberpayEnabled) {
        badges.push('SberPay');
      }
      if (payment?.mirpayEnabled) {
        badges.push('Mir Pay');
      }
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
      this.privacyConsent() &&
      this.receiptConsent() &&
      !this.sbpSubmitting() &&
      !this.bankSubmitting() &&
      !this.manualSubmitting()
    );
  });
  readonly canReportManual = computed(() => Boolean(
    this.payment()?.payable &&
    this.manualPayment() &&
    this.manualPaymentDestinationAvailable() &&
    this.payment()?.status !== 'MANUAL_REPORTED' &&
    !this.manualSubmitting()
  ));
  readonly manualPaymentButtonLabel = computed(() => {
    const label = this.payment()?.manualPaymentButtonLabel?.trim();
    return label || 'Оплатить через Альфа-Банк';
  });
  readonly manualPaymentUrl = computed(() => {
    return configuredPaymentTarget(this.payment()?.manualPaymentUrl);
  });
  readonly manualPaymentDestinationAvailable = computed(() => this.externalManualPayment()
    ? Boolean(this.manualPaymentUrl())
    : Boolean(this.payment()?.manualPhone?.trim()));
  readonly hasSbpLink = computed(() => Boolean(this.sbpPaymentPayload() || this.sbpPaymentUrl()));
  readonly featuredSbpBanks = computed(() => this.sbpBanks()
    .filter((bank) => bank.featured && this.isQuickAccessSbpBank(bank.name))
    .slice(0, 4));
  readonly hasSbpBanks = computed(() => this.sbpBanks().length > 0);
  readonly selectedSbpBank = computed(() => {
    const selectedId = this.selectedSbpBankId();
    return this.sbpBanks().find((bank) => bank.bankId === selectedId) ?? null;
  });
  readonly sbpButtonText = computed(() => {
    if (this.sbpSubmitting()) {
      return 'Открываем СБП...';
    }
    const selectedBank = this.selectedSbpBank();
    return selectedBank ? `Открыть в ${selectedBank.name}` : 'Открыть в СБП';
  });

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.routeEpoch.destroy();
      this.cancelRouteReads();
    });
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.activatePaymentRoute(params.get('token')));
  }

  @HostListener('window:pageshow')
  onPageShow(): void {
    this.refreshPaymentAfterReturn();
  }

  @HostListener('window:focus')
  onWindowFocus(): void {
    this.refreshPaymentAfterReturn();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (document.visibilityState === 'visible') {
      this.refreshPaymentAfterReturn();
    }
  }

  submitPrimaryPayment(): void {
    if (this.showSbpPayment()) {
      this.submitSbp();
      return;
    }
    this.submitBankForm();
  }

  submitSbp(bankId = this.selectedSbpBankId()): void {
    if (!this.canSubmit()) {
      this.message.set('Укажите e-mail и подтвердите согласия.');
      return;
    }

    const routeTicket = this.captureRoute();
    if (!routeTicket) {
      return;
    }
    const token = this.token();

    this.sbpSubmitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicSbpPayment(
      token,
      this.email().trim(),
      this.offerConsent(),
      this.privacyConsent(),
      this.receiptConsent(),
      bankId || null
    ).subscribe({
      next: (response) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.sbpPaymentPayload.set(response.qrPayload ?? '');
        this.sbpPaymentUrl.set(response.paymentUrl ?? '');
        if (response.qrPayload) {
          const bankName = bankId ? this.selectedSbpBank()?.name : '';
          this.sbpSubmitting.set(false);
          if (!this.navigatePayment(response.qrPayload, 'sbp')) {
            this.error.set('Банк вернул недопустимую ссылку СБП. Переход отменен.');
            return;
          }
          this.message.set(bankName
            ? `Открываем ${bankName}. Если приложение не открылось, нажмите кнопку еще раз.`
            : 'Открываем оплату через СБП. Если переход не сработал, нажмите кнопку еще раз.');
          return;
        }
        if (response.paymentUrl) {
          this.message.set('Банк не вернул ссылку СБП. Можно открыть обычную форму оплаты.');
          this.sbpSubmitting.set(false);
          return;
        }
        this.message.set('Банк не вернул ссылку СБП. Попробуйте запасной способ оплаты.');
        this.sbpSubmitting.set(false);
      },
      error: (err) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
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

    const routeTicket = this.captureRoute();
    if (!routeTicket) {
      return;
    }
    const token = this.token();

    this.bankSubmitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicPayment(
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
          if (this.navigatePayment(response.paymentUrl, 'payment')) {
            return;
          }
          this.error.set('Банк вернул недопустимую ссылку оплаты. Переход отменен.');
          this.bankSubmitting.set(false);
          return;
        }
        this.message.set('Банк не вернул ссылку на оплату. Попробуйте еще раз позже.');
        this.bankSubmitting.set(false);
      },
      error: (err) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.error.set(this.publicPaymentError(err));
        this.bankSubmitting.set(false);
      }
    });
  }

  reportManualPayment(): void {
    if (!this.canReportManual()) {
      return;
    }

    const routeTicket = this.captureRoute();
    if (!routeTicket) {
      return;
    }
    const token = this.token();

    this.manualSubmitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.reportPublicManualPayment(token).subscribe({
      next: (payment) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.payment.set(payment);
        this.manualSubmitting.set(false);
        this.message.set('Спасибо. Отметили платеж как отправленный, менеджер проверит поступление вручную.');
      },
      error: (err) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.error.set(this.publicPaymentError(err));
        this.manualSubmitting.set(false);
      }
    });
  }

  async copyManualValue(value?: string | null): Promise<void> {
    const text = value?.trim();
    if (!text) {
      return;
    }

    const routeTicket = this.captureRoute();
    if (!routeTicket) {
      return;
    }
    const copied = await copyTextToClipboard(text);
    if (!this.isActiveRoute(routeTicket)) {
      return;
    }
    if (copied) {
      this.message.set('Скопировано.');
    } else {
      this.message.set('Не получилось скопировать. Выделите текст вручную.');
    }
  }

  openManualPaymentUrl(): void {
    const url = this.manualPaymentUrl();
    if (!url) {
      return;
    }
    if (!this.navigatePayment(url, 'manual')) {
      this.error.set('Ссылка ручной оплаты имеет недопустимый формат.');
    }
  }

  selectSbpBank(bank: PublicSbpBank): void {
    this.setSbpBankId(bank.bankId);
  }

  setSbpBankId(bankId: string): void {
    this.selectedSbpBankId.set(bankId);
    this.sbpPaymentPayload.set('');
    this.sbpPaymentUrl.set('');
  }

  openSbpPayload(): void {
    const payload = this.sbpPaymentPayload().trim();
    if (!payload) {
      return;
    }
    if (!this.navigatePayment(payload, 'sbp')) {
      this.error.set('Ссылка СБП имеет недопустимый формат.');
    }
  }

  private navigatePayment(value: unknown, purpose: PaymentNavigationPurpose): boolean {
    return navigateToPaymentTarget(value, purpose, (target) => window.location.assign(target));
  }

  bankInitials(name?: string | null): string {
    const letters = (name ?? '')
      .split(/\s+/)
      .map((part) => part.trim().charAt(0))
      .filter(Boolean)
      .join('')
      .slice(0, 2)
      .toUpperCase();
    return letters || 'Б';
  }

  trackSbpBank(_index: number, bank: PublicSbpBank): string {
    return bank.bankId;
  }

  methodIcon(method: string): string {
    switch (method) {
      case 'T-Pay':
        return 'T';
      case 'SberPay':
        return 'S';
      case 'Mir Pay':
        return 'MIR';
      default:
        return 'Pay';
    }
  }

  formatRubles(amount?: number | null): string {
    return new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format(amount ?? 0);
  }

  private loadPayment(): void {
    const token = this.token();
    const routeTicket = this.captureRoute();
    if (!token || !routeTicket) {
      this.loading.set(false);
      this.error.set('Платежная ссылка не найдена.');
      return;
    }

    this.loading.set(true);
    this.paymentRouteRequest.start(this.paymentsApi.getPublicPaymentLink(token), {
      next: (payment) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.applyPayment(payment);
        this.loading.set(false);
      },
      error: (err) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.error.set(apiErrorMessage(err, 'Не удалось открыть платежную ссылку.'));
        this.loading.set(false);
      }
    });
  }

  private refreshPaymentAfterReturn(): void {
    const now = Date.now();
    if (
      now - this.lastReturnRefreshAt < 1200 ||
      this.loading() ||
      this.refreshingPayment() ||
      !this.token()
    ) {
      return;
    }

    const currentPayment = this.payment();
    if (!currentPayment || this.isFinalStatus(currentPayment.status)) {
      return;
    }

    this.lastReturnRefreshAt = now;
    this.refreshingPayment.set(true);
    const routeTicket = this.captureRoute();
    const token = this.token();
    if (!routeTicket || !token) {
      this.refreshingPayment.set(false);
      return;
    }
    this.paymentRouteRequest.start(this.paymentsApi.getPublicPaymentLink(token), {
      next: (payment) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.applyPayment(payment, true);
        this.refreshingPayment.set(false);
      },
      error: () => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.refreshingPayment.set(false);
      }
    });
  }

  private applyPayment(payment: PublicPaymentLink, preserveEmail = false): void {
    const typedEmail = this.email().trim();
    this.applyCanonicalToken(payment.token);
    this.payment.set(payment);
    if (!preserveEmail || !typedEmail) {
      this.email.set(payment.payerEmail ?? '');
    }
    this.sbpSubmitting.set(false);
    this.bankSubmitting.set(false);
    this.manualSubmitting.set(false);
    if (!payment.payable || this.isFinalStatus(payment.status)) {
      this.sbpPaymentPayload.set('');
      this.sbpPaymentUrl.set('');
    }
    if (
      payment.payable &&
      !this.manualPayment() &&
      payment.paymentPageMode !== 'BANK_ONLY' &&
      !this.hasSbpBanks() &&
      !this.sbpBanksLoading()
    ) {
      this.loadSbpBanks();
    }
  }

  private applyCanonicalToken(token?: string | null): void {
    const cleanToken = token?.trim();
    if (!cleanToken || cleanToken === this.token()) {
      return;
    }

    this.token.set(cleanToken);
    const replacementUrl = `/pay/${encodeURIComponent(cleanToken)}`;
    if (window.location.pathname !== replacementUrl) {
      window.history.replaceState(window.history.state, '', replacementUrl);
    }
  }

  private loadSbpBanks(): void {
    const token = this.token();
    const routeTicket = this.captureRoute();
    if (!token || !routeTicket) {
      return;
    }

    this.sbpBanksLoading.set(true);
    this.sbpBanksError.set('');
    this.sbpBanksRouteRequest.start(this.paymentsApi.getPublicSbpBanks(token), {
      next: (banks) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.sbpBanks.set(banks ?? []);
        const firstFeatured = this.featuredSbpBanks()[0] ?? this.sbpBanks()[0];
        if (firstFeatured && !this.selectedSbpBankId()) {
          this.selectedSbpBankId.set(firstFeatured.bankId);
        }
        this.sbpBanksLoading.set(false);
      },
      error: (err) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.sbpBanks.set([]);
        this.selectedSbpBankId.set('');
        this.sbpBanksError.set(apiErrorMessage(err, 'Не удалось загрузить банки СБП.'));
        this.sbpBanksLoading.set(false);
      }
    });
  }

  private activatePaymentRoute(rawToken: string | null): void {
    const token = rawToken?.trim() ?? '';
    const routeKey = token ? `pay:${token}` : 'pay:invalid';
    if (!this.routeEpoch.change(routeKey)) {
      return;
    }

    this.cancelRouteReads();
    this.clearRouteState();
    this.token.set(token);
    if (!token) {
      this.error.set('Платежная ссылка не найдена.');
      return;
    }
    this.loadPayment();
  }

  private clearRouteState(): void {
    this.payment.set(null);
    this.loading.set(false);
    this.sbpSubmitting.set(false);
    this.bankSubmitting.set(false);
    this.manualSubmitting.set(false);
    this.error.set('');
    this.message.set('');
    this.email.set('');
    this.offerConsent.set(false);
    this.privacyConsent.set(false);
    this.receiptConsent.set(false);
    this.sbpPaymentPayload.set('');
    this.sbpPaymentUrl.set('');
    this.sbpBanks.set([]);
    this.sbpBanksLoading.set(false);
    this.sbpBanksError.set('');
    this.selectedSbpBankId.set('');
    this.refreshingPayment.set(false);
    this.lastReturnRefreshAt = 0;
  }

  private cancelRouteReads(): void {
    this.paymentRouteRequest.cancel();
    this.sbpBanksRouteRequest.cancel();
    this.loading.set(false);
    this.refreshingPayment.set(false);
    this.sbpBanksLoading.set(false);
  }

  private captureRoute(): RouteEpochTicket | null {
    return this.routeEpoch.capture();
  }

  private isActiveRoute(ticket: RouteEpochTicket): boolean {
    return this.routeEpoch.accepts(ticket);
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
      case 'WAITING_MANUAL_PAYMENT':
        return 'Ожидает перевод';
      case 'MANUAL_REPORTED':
        return 'Платеж отправлен';
      case 'INITIATED':
      case 'AUTHORIZED':
        return 'Платеж начат';
      case 'CONFIRMED':
        return 'Оплачено';
      case 'TEST_CONFIRMED':
        return 'Тестовая оплата подтверждена';
      case 'EXPIRED':
        return 'Срок истек';
      case 'NEEDS_RECONCILIATION':
        return 'Сверяем платеж с банком';
      case 'CANCELED':
      case 'REJECTED':
        return 'Недоступна';
      default:
        return 'Проверяется';
    }
  }

  private isCompletedStatus(status?: string | null): boolean {
    return status === 'CONFIRMED' || status === 'TEST_CONFIRMED';
  }

  private isFinalStatus(status?: string | null): boolean {
    return this.isCompletedStatus(status) ||
      status === 'CANCELED' ||
      status === 'REJECTED' ||
      status === 'EXPIRED' ||
      status === 'FAILED' ||
      status === 'REFUNDED' ||
      status === 'PARTIAL_REFUNDED' ||
      status === 'REVERSED' ||
      status === 'PARTIAL_REVERSED';
  }

  private isQuickAccessSbpBank(name?: string | null): boolean {
    const clean = (name ?? '').trim().toLowerCase();
    return clean.includes('сбер') ||
      clean.includes('т-банк') ||
      clean.includes('t-банк') ||
      clean.includes('t-bank') ||
      clean.includes('тинькофф') ||
      clean.includes('альфа') ||
      clean.includes('втб');
  }
}
