import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';
import { ApiService, PublicPaymentLink, PublicSbpBank, TbankPaymentPageMode } from '../core/api.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { configuredPaymentTarget, type PaymentNavigationPurpose } from '../shared/payment-navigation';

@Component({
  selector: 'app-public-pay-page',
  imports: [FormsModule, IonContent, RouterLink],
  template: `
    <div class="ion-page">
      <ion-content fullscreen>
        <main class="pay-page">
          <header class="pay-hero">
            <a routerLink="/" class="brand">Компания <strong>О!</strong></a>
            <p>{{ statusLabel() }}</p>
            <h1>{{ title() }}</h1>
          </header>

          @if (loading()) {
            <section class="state-card"><span class="material-icons-sharp">hourglass_top</span><strong>Загружаем платеж</strong></section>
          }

          @if (error()) {
            <button class="state-card error" type="button" (click)="loadPayment()"><span class="material-icons-sharp">error</span><strong>{{ error() }}</strong></button>
          }

          @if (message()) {
            <section class="state-card ok"><span class="material-icons-sharp">info</span><strong>{{ message() }}</strong></section>
          }

          @if (payment(); as payment) {
            <section class="summary-card">
              <div>
                <small>Компания</small>
                <strong>{{ payment.companyTitle || 'Компания' }}</strong>
              </div>
              <div>
                <small>Услуга</small>
                <strong>{{ payment.serviceTitle || payment.description || 'Репутационное сопровождение' }}</strong>
              </div>
              <div>
                <small>Сумма</small>
                <b>{{ formatRubles(payment.amount) }} ₽</b>
              </div>
              <div>
                <small>Статус</small>
                <strong>{{ statusLabel() }}</strong>
              </div>
            </section>

            @if (isPaymentComplete()) {
              <section class="done-card">
                <span class="material-icons-sharp">task_alt</span>
                <h2>{{ paymentCompleteTitle() }}</h2>
                <p>{{ paymentCompleteText() }}</p>
              </section>
            } @else if (payment.payable) {
              <form class="pay-form" (ngSubmit)="submitPrimaryPayment()">
                <label>
                  <span>E-mail для чека</span>
                  <input name="email" type="email" autocomplete="email" [ngModel]="email()" (ngModelChange)="email.set($event)">
                </label>

                <label class="check-row"><input type="checkbox" [ngModel]="offerConsent()" (ngModelChange)="offerConsent.set($event)" name="offer"><span>Согласен с <a routerLink="/offer">офертой</a>.</span></label>
                <label class="check-row"><input type="checkbox" [ngModel]="privacyConsent()" (ngModelChange)="privacyConsent.set($event)" name="privacy"><span>Согласен с <a routerLink="/privacy">политикой персональных данных</a>.</span></label>
                <label class="check-row"><input type="checkbox" [ngModel]="receiptConsent()" (ngModelChange)="receiptConsent.set($event)" name="receipt"><span>Согласен получить <a routerLink="/receipt-consent">электронный чек</a>.</span></label>

                @if (manualPayment()) {
                  <section class="manual-card">
                    <h2>{{ externalManualPayment() ? manualPaymentButtonLabel() : (payment.manualPhone || 'Телефон не указан') }}</h2>
                    <p>{{ payment.manualRecipientName || 'Получатель не указан' }}</p>
                    <small>{{ payment.manualComment || 'После оплаты нажмите кнопку подтверждения.' }}</small>
                    @if (manualPaymentDestinationAvailable()) {
                    <button type="button" (click)="externalManualPayment() ? openManualPaymentUrl() : copyManualValue(payment.manualPhone)">
                      <span class="material-icons-sharp">{{ externalManualPayment() ? 'open_in_new' : 'content_copy' }}</span>
                      {{ externalManualPayment() ? 'Открыть оплату' : 'Скопировать телефон' }}
                    </button>
                    } @else {
                    <p class="destination-error" role="alert">Реквизиты не настроены. Не переводите деньги и обратитесь к менеджеру.</p>
                    }
                    <button type="button" class="primary" (click)="reportManualPayment()" [disabled]="!canReportManual()">
                      {{ manualSubmitting() ? 'Отмечаем...' : 'Я оплатил' }}
                    </button>
                  </section>
                } @else {
                  @if (showSbpPayment()) {
                    <section class="sbp-card">
                      <label>
                        <span>Банк СБП</span>
                        <select name="sbpBank" [ngModel]="selectedSbpBankId()" (ngModelChange)="setSbpBankId($event)">
                          <option value="">Любой банк</option>
                          @for (bank of sbpBanks(); track bank.bankId) {
                            <option [value]="bank.bankId">{{ bank.name }}</option>
                          }
                        </select>
                      </label>
                    </section>
                  }

                  <button class="primary" type="submit" [disabled]="!canSubmit()">
                    <span class="material-icons-sharp">{{ primaryButtonIcon() }}</span>
                    {{ primaryButtonText() }}
                  </button>

                  @if (showBankPayment() && showSbpPayment()) {
                    <button type="button" (click)="submitBankForm()" [disabled]="!canSubmit()">Открыть форму банка</button>
                  }
                }
              </form>
            } @else {
              <section class="state-card"><span class="material-icons-sharp">lock</span><strong>Эта ссылка недоступна для оплаты.</strong></section>
            }
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content{--background:#f6f8fc}.pay-page{display:grid;gap:.75rem;max-width:42rem;margin:0 auto;padding:calc(1rem + env(safe-area-inset-top)) .85rem calc(1.2rem + env(safe-area-inset-bottom));font-family:var(--otziv-font-family)}
    .pay-hero,.summary-card,.pay-form,.state-card,.done-card,.manual-card,.sbp-card{border:1px solid rgba(103,116,131,.16);border-radius:1rem;background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 .9rem 1.8rem rgba(132,139,200,.12)}
    .pay-hero{display:grid;gap:.45rem;padding:1rem}.brand{color:var(--otziv-dark);font:900 1.1rem/1 var(--otziv-card-title-font);text-decoration:none}.brand strong{color:var(--otziv-danger)}.pay-hero p{margin:0;color:var(--otziv-info);font-size:.7rem;font-weight:1000;text-transform:uppercase}.pay-hero h1{margin:0;color:var(--otziv-dark);font-size:1.75rem}
    .summary-card{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.55rem;padding:.8rem}.summary-card div{display:grid;gap:.12rem;min-width:0}.summary-card small,.pay-form label>span{color:var(--otziv-info);font-size:.66rem;font-weight:1000;text-transform:uppercase}.summary-card strong,.summary-card b{overflow:hidden;color:var(--otziv-dark);font-size:.9rem;text-overflow:ellipsis}.summary-card b{color:#16735f;font-size:1.2rem}
    .pay-form{display:grid;gap:.62rem;padding:.85rem}.pay-form label{display:grid;gap:.3rem}.pay-form input,.pay-form select{min-height:2.5rem;border:1px solid rgba(103,116,131,.18);border-radius:.75rem;padding:0 .75rem;color:var(--otziv-dark);background:var(--otziv-white);font:900 .9rem/1 var(--otziv-font-family)}.check-row{grid-template-columns:auto minmax(0,1fr);align-items:center}.check-row input{min-height:1rem}.check-row span{color:var(--otziv-dark);font-size:.75rem;text-transform:none}.check-row a{color:var(--otziv-primary)}
    button{display:inline-flex;align-items:center;justify-content:center;gap:.35rem;min-height:2.55rem;border:1px solid rgba(108,155,207,.25);border-radius:.82rem;color:var(--otziv-primary);background:var(--otziv-white);font:1000 .82rem/1 var(--otziv-font-family)}button.primary{color:#fff;background:var(--otziv-primary)}button:disabled{opacity:.55}.state-card,.done-card{display:grid;place-items:center;gap:.35rem;min-height:5.5rem;padding:1rem;text-align:center}.state-card.error{color:var(--otziv-danger)}.state-card.ok,.done-card{color:#16735f}.done-card h2,.done-card p{margin:0}.done-card p{color:var(--otziv-info);font-weight:800}.manual-card,.sbp-card{display:grid;gap:.45rem;padding:.75rem}.manual-card h2,.manual-card p{margin:0}.manual-card small{color:var(--otziv-info);font-weight:800}.manual-card .destination-error{margin:0;color:var(--otziv-danger);font-weight:900}
  `]
})
export class PublicPayPage {
  private readonly route = inject(ActivatedRoute);

  readonly token = signal(this.route.snapshot.paramMap.get('token') ?? '');
  readonly payment = signal<PublicPaymentLink | null>(null);
  readonly loading = signal(true);
  readonly refreshingPayment = signal(false);
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
  readonly sbpBanks = signal<PublicSbpBank[]>([]);
  readonly selectedSbpBankId = signal('');
  private lastReturnRefreshAt = 0;

  readonly title = computed(() => this.payment()?.payable ? 'Оплата заказа' : 'Платежная ссылка');
  readonly manualPayment = computed(() => ['MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK'].includes(this.payment()?.paymentMethod ?? ''));
  readonly externalManualPayment = computed(() => this.payment()?.paymentMethod === 'MANUAL_EXTERNAL_LINK' || this.payment()?.manualPaymentType === 'EXTERNAL_LINK');
  readonly statusLabel = computed(() => this.statusText(this.payment()?.status));
  readonly paymentPageMode = computed<TbankPaymentPageMode>(() => this.payment()?.paymentPageMode ?? 'SBP_PRIMARY');
  readonly showSbpPayment = computed(() => !this.manualPayment() && this.paymentPageMode() !== 'BANK_ONLY');
  readonly showBankPayment = computed(() => !this.manualPayment() && this.paymentPageMode() !== 'SBP_ONLY' && this.paymentPageMode() !== 'SBP_PAY_ONLY');
  readonly isPaymentComplete = computed(() => ['CONFIRMED', 'TEST_CONFIRMED'].includes(this.payment()?.status ?? ''));
  readonly canSubmit = computed(() => Boolean(this.payment()?.payable && this.email().includes('@') && this.offerConsent() && this.privacyConsent() && this.receiptConsent() && !this.sbpSubmitting() && !this.bankSubmitting()));
  readonly canReportManual = computed(() => Boolean(this.payment()?.payable && this.manualPayment() && this.manualPaymentDestinationAvailable() && this.payment()?.status !== 'MANUAL_REPORTED' && !this.manualSubmitting()));
  readonly manualPaymentButtonLabel = computed(() => this.payment()?.manualPaymentButtonLabel?.trim() || 'Оплатить через Альфа-Банк');
  readonly manualPaymentUrl = computed(() => configuredPaymentTarget(this.payment()?.manualPaymentUrl));
  readonly manualPaymentDestinationAvailable = computed(() => this.externalManualPayment()
    ? Boolean(this.manualPaymentUrl())
    : Boolean(this.payment()?.manualPhone?.trim()));
  readonly primaryButtonIcon = computed(() => this.showSbpPayment() ? (this.sbpSubmitting() ? 'hourglass_top' : 'account_balance_wallet') : (this.bankSubmitting() ? 'hourglass_top' : 'payments'));
  readonly primaryButtonText = computed(() => this.showSbpPayment() ? (this.sbpSubmitting() ? 'Открываем СБП...' : 'Открыть в СБП') : (this.bankSubmitting() ? 'Открываем банк...' : 'Открыть форму банка'));
  readonly paymentCompleteTitle = computed(() => this.payment()?.status === 'TEST_CONFIRMED' ? 'Тестовая оплата подтверждена' : 'Оплата прошла успешно');
  readonly paymentCompleteText = computed(() => this.payment()?.status === 'TEST_CONFIRMED' ? 'Повторная оплата по этой ссылке больше не нужна.' : 'Электронный чек будет отправлен на указанный e-mail.');

  constructor(
    private readonly api: ApiService,
    private readonly externalLink: MobileExternalLinkService
  ) {
    this.loadPayment();
  }

  @HostListener('window:pageshow') onPageShow(): void { this.refreshPaymentAfterReturn(); }
  @HostListener('window:focus') onWindowFocus(): void { this.refreshPaymentAfterReturn(); }
  @HostListener('document:visibilitychange') onVisibilityChange(): void {
    if (document.visibilityState === 'visible') {
      this.refreshPaymentAfterReturn();
    }
  }

  submitPrimaryPayment(): void {
    if (this.showSbpPayment()) {
      this.submitSbp();
    } else {
      this.submitBankForm();
    }
  }

  submitSbp(): void {
    if (!this.canSubmit()) {
      this.message.set('Укажите e-mail и подтвердите согласия.');
      return;
    }
    this.sbpSubmitting.set(true);
    this.clearFeedback();
    this.api.initPublicSbpPayment(this.token(), this.email().trim(), this.offerConsent(), this.privacyConsent(), this.receiptConsent(), this.selectedSbpBankId() || null).subscribe({
      next: (response) => {
        this.sbpSubmitting.set(false);
        if (response.qrPayload) {
          this.openPaymentTarget(response.qrPayload, 'sbp');
          return;
        }
        if (response.paymentUrl) {
          this.openPaymentTarget(response.paymentUrl, 'payment');
          return;
        }
        this.message.set('Банк не вернул ссылку СБП. Попробуйте форму банка.');
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось перейти к оплате.'));
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
    this.clearFeedback();
    this.api.initPublicPayment(this.token(), this.email().trim(), this.offerConsent(), this.privacyConsent(), this.receiptConsent()).subscribe({
      next: (response) => {
        this.bankSubmitting.set(false);
        if (response.paymentUrl) {
          this.openPaymentTarget(response.paymentUrl, 'payment');
          return;
        }
        this.message.set('Банк не вернул ссылку на оплату.');
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось перейти к оплате.'));
        this.bankSubmitting.set(false);
      }
    });
  }

  reportManualPayment(): void {
    if (!this.canReportManual()) {
      return;
    }
    this.manualSubmitting.set(true);
    this.clearFeedback();
    this.api.reportPublicManualPayment(this.token()).subscribe({
      next: (payment) => {
        this.payment.set(payment);
        this.manualSubmitting.set(false);
        this.message.set('Спасибо. Менеджер проверит поступление вручную.');
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось отметить оплату.'));
        this.manualSubmitting.set(false);
      }
    });
  }

  async copyManualValue(value?: string | null): Promise<void> {
    if (!value?.trim()) {
      return;
    }
    await navigator.clipboard.writeText(value.trim()).then(() => this.message.set('Скопировано.'));
  }

  openManualPaymentUrl(): void {
    const target = this.manualPaymentUrl();
    if (!target) {
      this.error.set('Ссылка оплаты не настроена. Обратитесь к менеджеру.');
      return;
    }
    this.openPaymentTarget(target, 'manual');
  }

  private openPaymentTarget(value: unknown, purpose: PaymentNavigationPurpose): void {
    void this.externalLink.openPayment(value, purpose).then((opened) => {
      if (!opened) {
        this.error.set('Ссылка оплаты имеет недопустимый формат. Переход отменен.');
      }
    }).catch(() => this.error.set('Не удалось открыть ссылку оплаты.'));
  }

  setSbpBankId(value: string): void {
    this.selectedSbpBankId.set(value);
  }

  formatRubles(amount?: number | null): string {
    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(amount ?? 0);
  }

  loadPayment(): void {
    if (!this.token()) {
      this.loading.set(false);
      this.error.set('Платежная ссылка не найдена.');
      return;
    }
    this.loading.set(true);
    this.api.getPublicPaymentLink(this.token()).subscribe({
      next: (payment) => {
        this.applyPayment(payment);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось открыть платежную ссылку.'));
        this.loading.set(false);
      }
    });
  }

  private applyPayment(payment: PublicPaymentLink): void {
    this.payment.set(payment);
    this.email.set(payment.payerEmail ?? this.email());
    if (payment.payable && this.showSbpPayment() && !this.sbpBanks().length) {
      this.api.getPublicSbpBanks(payment.token).subscribe({
        next: (banks) => this.sbpBanks.set(banks ?? []),
        error: () => this.sbpBanks.set([])
      });
    }
  }

  private refreshPaymentAfterReturn(): void {
    const now = Date.now();
    if (now - this.lastReturnRefreshAt < 1200 || this.loading() || this.refreshingPayment() || !this.token() || this.isPaymentComplete()) {
      return;
    }
    this.lastReturnRefreshAt = now;
    this.refreshingPayment.set(true);
    this.api.getPublicPaymentLink(this.token()).subscribe({
      next: (payment) => {
        this.applyPayment(payment);
        this.refreshingPayment.set(false);
      },
      error: () => this.refreshingPayment.set(false)
    });
  }

  private clearFeedback(): void {
    this.error.set('');
    this.message.set('');
  }

  private statusText(status?: string): string {
    return {
      CREATED: 'Готова к оплате',
      WAITING_MANUAL_PAYMENT: 'Ожидает перевод',
      MANUAL_REPORTED: 'Платеж отправлен',
      INITIATED: 'Платеж начат',
      AUTHORIZED: 'Платеж начат',
      CONFIRMED: 'Оплачено',
      TEST_CONFIRMED: 'Тестовая оплата подтверждена',
      NEEDS_RECONCILIATION: 'Сверяем платеж с банком',
      EXPIRED: 'Срок истек',
      CANCELED: 'Недоступна',
      REJECTED: 'Недоступна'
    }[status ?? ''] ?? 'Проверяется';
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (typeof error === 'object' && error && 'error' in error) {
      const body = (error as { error?: { message?: string; detail?: string; error?: string } | string }).error;
      return typeof body === 'string' ? body : body?.message || body?.detail || body?.error || fallback;
    }
    return fallback;
  }
}
