import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { apiErrorMessage } from '../../shared/api-error-message';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { PaymentsApi, PublicPaymentLink, PublicSbpBank, TbankPaymentPageMode } from '../../core/payments.api';

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
  readonly manualSubmitting = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly email = signal('');
  readonly offerConsent = signal(false);
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
      this.receiptConsent() &&
      !this.sbpSubmitting() &&
      !this.bankSubmitting() &&
      !this.manualSubmitting()
    );
  });
  readonly canReportManual = computed(() => Boolean(
    this.payment()?.payable &&
    this.manualPayment() &&
    this.payment()?.status !== 'MANUAL_REPORTED' &&
    !this.manualSubmitting()
  ));
  readonly manualPaymentButtonLabel = computed(() => {
    const label = this.payment()?.manualPaymentButtonLabel?.trim();
    return label || 'Оплатить через Альфа-Банк';
  });
  readonly manualPaymentUrl = computed(() => {
    const url = this.payment()?.manualPaymentUrl?.trim();
    return url || 'https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR';
  });
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
    this.loadPayment();
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

    this.sbpSubmitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.initPublicSbpPayment(
      this.token(),
      this.email().trim(),
      this.offerConsent(),
      this.offerConsent(),
      this.receiptConsent(),
      bankId || null
    ).subscribe({
      next: (response) => {
        this.sbpPaymentPayload.set(response.qrPayload ?? '');
        this.sbpPaymentUrl.set(response.paymentUrl ?? '');
        if (response.qrPayload) {
          const bankName = bankId ? this.selectedSbpBank()?.name : '';
          this.message.set(bankName
            ? `Открываем ${bankName}. Если приложение не открылось, нажмите кнопку еще раз.`
            : 'Открываем оплату через СБП. Если переход не сработал, нажмите кнопку еще раз.');
          this.sbpSubmitting.set(false);
          window.location.href = response.qrPayload;
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

    const bankWindow = this.openBlankBankWindow();
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
          if (bankWindow && !bankWindow.closed) {
            bankWindow.location.href = response.paymentUrl;
            this.message.set('Открыли форму банка в новой вкладке. После оплаты вернитесь сюда: статус обновится автоматически.');
            this.bankSubmitting.set(false);
            return;
          }
          this.message.set('Браузер заблокировал новую вкладку, поэтому открываем форму банка здесь.');
          window.location.href = response.paymentUrl;
          return;
        }
        this.closeBankWindow(bankWindow);
        this.message.set('Банк не вернул ссылку на оплату. Попробуйте еще раз позже.');
        this.bankSubmitting.set(false);
      },
      error: (err) => {
        this.closeBankWindow(bankWindow);
        this.error.set(this.publicPaymentError(err));
        this.bankSubmitting.set(false);
      }
    });
  }

  reportManualPayment(): void {
    if (!this.canReportManual()) {
      return;
    }

    this.manualSubmitting.set(true);
    this.message.set('');
    this.error.set('');
    this.paymentsApi.reportPublicManualPayment(this.token()).subscribe({
      next: (payment) => {
        this.payment.set(payment);
        this.manualSubmitting.set(false);
        this.message.set('Спасибо. Отметили платеж как отправленный, менеджер проверит поступление вручную.');
      },
      error: (err) => {
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
    if (await copyTextToClipboard(text)) {
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
    window.location.href = url;
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
    window.location.href = payload;
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
      // The browser may deny access after a navigation attempt; nothing else is needed.
    }
  }

  private loadPayment(): void {
    if (!this.token()) {
      this.loading.set(false);
      this.error.set('Платежная ссылка не найдена.');
      return;
    }

    this.paymentsApi.getPublicPaymentLink(this.token()).subscribe({
      next: (payment) => {
        this.applyPayment(payment);
        this.loading.set(false);
      },
      error: (err) => {
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
    this.paymentsApi.getPublicPaymentLink(this.token()).subscribe({
      next: (payment) => {
        this.applyPayment(payment, true);
        this.refreshingPayment.set(false);
      },
      error: () => {
        this.refreshingPayment.set(false);
      }
    });
  }

  private applyPayment(payment: PublicPaymentLink, preserveEmail = false): void {
    const typedEmail = this.email().trim();
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

  private loadSbpBanks(): void {
    if (!this.token()) {
      return;
    }

    this.sbpBanksLoading.set(true);
    this.sbpBanksError.set('');
    this.paymentsApi.getPublicSbpBanks(this.token()).subscribe({
      next: (banks) => {
        this.sbpBanks.set(banks ?? []);
        const firstFeatured = this.featuredSbpBanks()[0] ?? this.sbpBanks()[0];
        if (firstFeatured && !this.selectedSbpBankId()) {
          this.selectedSbpBankId.set(firstFeatured.bankId);
        }
        this.sbpBanksLoading.set(false);
      },
      error: (err) => {
        this.sbpBanks.set([]);
        this.selectedSbpBankId.set('');
        this.sbpBanksError.set(apiErrorMessage(err, 'Не удалось загрузить банки СБП.'));
        this.sbpBanksLoading.set(false);
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
