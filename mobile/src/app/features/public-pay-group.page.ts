import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';
import { Subscription } from 'rxjs';
import { ApiService, PublicCommonInvoice } from '../core/api.service';
import { RouteEpochGuard, RouteEpochTicket } from '../core/route-epoch.guard';
import { manualTransferDestinationPresentation } from '../shared/manual-transfer-destination';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { configuredPaymentTarget } from '../shared/payment-navigation';
import { isBankPaymentRoute } from '../shared/bank-payment-source';

@Component({
  selector: 'app-public-pay-group-page',
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
            <section class="state-card"><span class="material-icons-sharp">hourglass_top</span><strong>Загружаем счет</strong></section>
          }
          @if (error()) {
            <button class="state-card error" type="button" (click)="loadInvoice()"><span class="material-icons-sharp">error</span><strong>{{ error() }}</strong></button>
          }
          @if (message()) {
            <section class="state-card ok"><span class="material-icons-sharp">info</span><strong>{{ message() }}</strong></section>
          }

          @if (invoice(); as invoice) {
            <section class="summary-card">
              <div><small>Счет</small><strong>{{ invoice.title || 'Общий счет' }}</strong></div>
              <div><small>Аккаунт</small><strong>{{ invoice.accountName || '-' }}</strong></div>
              <div><small>Осталось</small><b>{{ formatRubles(invoice.remaining) }} ₽</b></div>
              <div><small>Готово</small><strong>{{ readyOrders() }} из {{ invoice.orders.length }}</strong></div>
            </section>

            <section class="order-list">
              @for (order of invoice.orders; track order.orderId) {
                <article [class.paid]="order.paid">
                  <div>
                    <strong>#{{ order.orderId }} {{ order.companyTitle }}</strong>
                    <small>{{ order.filialTitle || order.orderStatus }}</small>
                  </div>
                  <b>{{ formatRubles(order.amount) }} ₽</b>
                </article>
              }
            </section>

            @if (invoice.status === 'PAID') {
              <section class="state-card ok"><span class="material-icons-sharp">task_alt</span><strong>Общий счет оплачен.</strong></section>
            } @else if (managerTextRoute()) {
              <section class="manual-card">
                <h2>Реквизиты менеджера</h2>
                <p>{{ invoice.paymentInstructionText || 'Запросите реквизиты у менеджера.' }}</p>
                <small>После оплаты отправьте один чек менеджеру.</small>
              </section>
            } @else if (manualRoute()) {
              <section class="manual-card" aria-label="Реквизиты общего счета">
                <h2>{{ manualRouteTitle() }}</h2>
                @if (externalManualRoute()) {
                  @if (manualPaymentUrl()) {
                    <button class="primary" type="button" (click)="openManualPaymentUrl()">
                      <span class="material-icons-sharp">open_in_new</span>
                      {{ manualPaymentButtonLabel() }}
                    </button>
                    <button class="detail-line" type="button" (click)="copyPaymentValue(manualPaymentUrl())">
                      <small>Ссылка оплаты</small><strong>{{ manualPaymentUrl() }}</strong><span class="material-icons-sharp">content_copy</span>
                    </button>
                  } @else {
                    <p class="destination-error">Ссылка оплаты не настроена. Обратитесь к менеджеру.</p>
                  }
                } @else {
                  @if (invoice.manualPhone?.trim()) {
                    <button class="detail-line" type="button" (click)="copyPaymentValue(invoice.manualPhone)" [title]="manualTransferDestination().copyLabel">
                      <small>{{ manualTransferDestinationLabel() }}</small><strong>{{ invoice.manualPhone }}</strong><span class="material-icons-sharp">content_copy</span>
                    </button>
                  } @else {
                    <p class="destination-error">Реквизиты для перевода не настроены. Не переводите деньги и обратитесь к менеджеру.</p>
                  }
                }
                @if (invoice.manualRecipientName?.trim()) {
                  <button class="detail-line" type="button" (click)="copyPaymentValue(invoice.manualRecipientName)">
                    <small>Получатель</small><strong>{{ invoice.manualRecipientName }}</strong><span class="material-icons-sharp">content_copy</span>
                  </button>
                }
                @if (invoice.manualBankName?.trim()) {
                  <button class="detail-line" type="button" (click)="copyPaymentValue(invoice.manualBankName)">
                    <small>Банк получателя</small><strong>{{ invoice.manualBankName }}</strong><span class="material-icons-sharp">content_copy</span>
                  </button>
                }
                @if (invoice.manualComment?.trim()) {
                  <button class="detail-line" type="button" (click)="copyPaymentValue(invoice.manualComment)">
                    <small>Комментарий</small><strong>{{ invoice.manualComment }}</strong><span class="material-icons-sharp">content_copy</span>
                  </button>
                }
                <small>После оплаты отправьте один чек менеджеру.</small>
                @if (invoice.clientReportedAt) {
                  <p class="reported-state"><span class="material-icons-sharp">schedule</span> Вы сообщили об оплате. Ожидаем подтверждение поступления.</p>
                } @else if (invoice.clientReportable) {
                  <button class="primary" type="button" (click)="reportPaid()" [disabled]="!canReportPaid()">
                    <span class="material-icons-sharp">{{ reportingPaid() ? 'hourglass_top' : 'done_all' }}</span>
                    {{ reportingPaid() ? 'Отправляем сообщение...' : 'Я оплатил' }}
                  </button>
                }
              </section>
            } @else if (invoice.payable && bankRoute()) {
              <form class="pay-form" (ngSubmit)="submitPayment()">
                <label>
                  <span>E-mail для чека</span>
                  <input name="email" type="email" autocomplete="email" [ngModel]="email()" (ngModelChange)="email.set($event)">
                </label>
                <label class="check-row"><input type="checkbox" name="offer" [ngModel]="offerConsent()" (ngModelChange)="offerConsent.set($event)"><span>Согласен с <a routerLink="/offer">офертой</a>.</span></label>
                <label class="check-row"><input type="checkbox" name="privacy" [ngModel]="privacyConsent()" (ngModelChange)="privacyConsent.set($event)"><span>Согласен с <a routerLink="/privacy">политикой персональных данных</a>.</span></label>
                <label class="check-row"><input type="checkbox" name="receipt" [ngModel]="receiptConsent()" (ngModelChange)="receiptConsent.set($event)"><span>Согласен получить <a routerLink="/receipt-consent">электронный чек</a>.</span></label>
                <button class="primary" type="submit" [disabled]="!canSubmit()">
                  <span class="material-icons-sharp">{{ submitting() ? 'hourglass_top' : 'payments' }}</span>
                  {{ submitting() ? 'Открываем банк...' : 'Оплатить счет' }}
                </button>
                <p class="cert-help">
                  Если сайт банка попросит сертификат, установите сертификаты Минцифры на
                  <a href="https://www.gosuslugi.ru/crt" target="_blank" rel="noopener noreferrer">Госуслугах</a>.
                </p>
              </form>
            } @else if (invoice.payable) {
              <section class="state-card"><span class="material-icons-sharp">support_agent</span><strong>Способ оплаты ещё не подготовлен. Обратитесь к менеджеру.</strong></section>
            } @else {
              <section class="state-card"><span class="material-icons-sharp">lock</span><strong>Этот счет недоступен для оплаты.</strong></section>
            }
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content{--background:#f6f8fc}.pay-page{display:grid;gap:.75rem;max-width:42rem;margin:0 auto;padding:calc(1rem + env(safe-area-inset-top)) .85rem calc(1.2rem + env(safe-area-inset-bottom));font-family:var(--otziv-font-family)}
    .pay-hero,.summary-card,.pay-form,.manual-card,.state-card,.order-list article{border:1px solid rgba(103,116,131,.16);border-radius:1rem;background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 .9rem 1.8rem rgba(132,139,200,.12)}
    .pay-hero{display:grid;gap:.45rem;padding:1rem}.brand{color:var(--otziv-dark);font:900 1.1rem/1 var(--otziv-card-title-font);text-decoration:none}.brand strong{color:var(--otziv-danger)}.pay-hero p{margin:0;color:var(--otziv-info);font-size:.7rem;font-weight:1000;text-transform:uppercase}.pay-hero h1{margin:0;color:var(--otziv-dark);font-size:1.75rem}
    .summary-card{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.55rem;padding:.8rem}.summary-card div{display:grid;gap:.12rem}.summary-card small,.pay-form label>span{color:var(--otziv-info);font-size:.66rem;font-weight:1000;text-transform:uppercase}.summary-card strong,.summary-card b{overflow:hidden;color:var(--otziv-dark);font-size:.9rem;text-overflow:ellipsis}.summary-card b{color:#16735f;font-size:1.2rem}
    .order-list{display:grid;gap:.5rem}.order-list article{display:grid;grid-template-columns:minmax(0,1fr)auto;gap:.55rem;align-items:center;padding:.75rem}.order-list article.paid{opacity:.68}.order-list strong,.order-list small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.order-list small{color:var(--otziv-info);font-weight:800}.order-list b{color:#16735f}
    .pay-form{display:grid;gap:.62rem;padding:.85rem}.pay-form label{display:grid;gap:.3rem}.pay-form input{min-height:2.5rem;border:1px solid rgba(103,116,131,.18);border-radius:.75rem;padding:0 .75rem;color:var(--otziv-dark);background:var(--otziv-white);font:900 .9rem/1 var(--otziv-font-family)}.check-row{grid-template-columns:auto minmax(0,1fr);align-items:center}.check-row input{min-height:1rem}.check-row span{color:var(--otziv-dark);font-size:.75rem;text-transform:none}.check-row a{color:var(--otziv-primary)}
    button{display:inline-flex;align-items:center;justify-content:center;gap:.35rem;min-height:2.55rem;border:1px solid rgba(108,155,207,.25);border-radius:.82rem;color:var(--otziv-primary);background:var(--otziv-white);font:1000 .82rem/1 var(--otziv-font-family)}button.primary{color:#fff;background:var(--otziv-primary)}button:disabled{opacity:.55}.cert-help{margin:0;color:var(--otziv-info);font-size:.7rem;font-weight:800;line-height:1.35;text-align:center}.cert-help a{color:var(--otziv-primary);font-weight:1000}.state-card{display:grid;place-items:center;gap:.35rem;min-height:5.5rem;padding:1rem;text-align:center}.state-card.error{color:var(--otziv-danger)}.state-card.ok{color:#16735f}.manual-card{display:grid;gap:.55rem;padding:.85rem}.manual-card h2,.manual-card p{margin:0}.manual-card>small{color:var(--otziv-info);font-weight:800}.manual-card .detail-line{display:grid;grid-template-columns:minmax(0,1fr)auto;justify-items:start;text-align:left;padding:.65rem .75rem}.detail-line small,.detail-line strong{grid-column:1}.detail-line small{color:var(--otziv-info);font-size:.66rem;text-transform:uppercase}.detail-line strong{overflow-wrap:anywhere;color:var(--otziv-dark)}.detail-line .material-icons-sharp{grid-column:2;grid-row:1/3;align-self:center}.destination-error{color:var(--otziv-danger);font-weight:900}.reported-state{display:flex;align-items:center;gap:.35rem;color:#16735f;font-weight:900}
  `]
})
export class PublicPayGroupPage implements OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly routeEpoch = new RouteEpochGuard();
  private routeSubscription?: Subscription;
  private invoiceLoadSubscription?: Subscription;

  readonly token = signal('');
  readonly invoice = signal<PublicCommonInvoice | null>(null);
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly submitting = signal(false);
  readonly reportingPaid = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly email = signal('');
  readonly offerConsent = signal(false);
  readonly privacyConsent = signal(false);
  readonly receiptConsent = signal(false);
  private lastReturnRefreshAt = 0;

  readonly title = computed(() => this.invoice()?.title || 'Общий счет');
  readonly statusLabel = computed(() => this.statusText(this.invoice()?.status));
  readonly readyOrders = computed(() => this.invoice()?.orders.filter((order) => order.ready).length ?? 0);
  readonly paymentRouteType = computed(() => (this.invoice()?.paymentRouteType ?? '').trim().toUpperCase());
  readonly bankRoute = computed(() => isBankPaymentRoute(this.paymentRouteType()));
  readonly managerTextRoute = computed(() => this.paymentRouteType() === 'MANAGER_TEXT');
  readonly manualRoute = computed(() => ['MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK'].includes(this.paymentRouteType()));
  readonly externalManualRoute = computed(() => this.paymentRouteType() === 'MANUAL_EXTERNAL_LINK'
    || this.invoice()?.manualPaymentType === 'EXTERNAL_LINK');
  readonly manualPaymentUrl = computed(() => configuredPaymentTarget(this.invoice()?.manualPaymentUrl));
  readonly manualPaymentButtonLabel = computed(() => this.invoice()?.manualPaymentButtonLabel?.trim() || 'Открыть ссылку оплаты');
  readonly manualTransferDestination = computed(() => manualTransferDestinationPresentation(this.invoice()?.manualPhone));
  readonly manualTransferDestinationLabel = computed(() => this.manualTransferDestination().fieldLabel);
  readonly manualRouteTitle = computed(() => this.externalManualRoute()
    ? 'Оплата по ссылке банка'
    : this.manualTransferDestination().paymentTitle);
  readonly canSubmit = computed(() => Boolean(
    this.invoice()?.payable
      && this.bankRoute()
      && this.email().includes('@')
      && this.offerConsent()
      && this.privacyConsent()
      && this.receiptConsent()
      && !this.submitting()
  ));
  readonly canReportPaid = computed(() => Boolean(
    this.invoice()?.clientReportable
      && this.manualRoute()
      && !this.invoice()?.clientReportedAt
      && !this.reportingPaid()
  ));

  constructor(
    private readonly api: ApiService,
    private readonly externalLink: MobileExternalLinkService
  ) {
    this.routeSubscription = this.route.paramMap.subscribe((params) => {
      this.activateInvoiceRoute(params.get('token'));
    });
  }

  ngOnDestroy(): void {
    this.routeEpoch.destroy();
    this.cancelRouteRead();
    this.routeSubscription?.unsubscribe();
  }

  @HostListener('window:pageshow') onPageShow(): void { this.refreshAfterReturn(); }
  @HostListener('window:focus') onWindowFocus(): void { this.refreshAfterReturn(); }
  @HostListener('document:visibilitychange') onVisibilityChange(): void {
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
    this.error.set('');
    this.message.set('');
    this.api.initPublicCommonInvoicePayment(token, this.email().trim(), this.offerConsent(), this.privacyConsent(), this.receiptConsent()).subscribe({
      next: (response) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.submitting.set(false);
        if (response.paymentUrl) {
          void this.externalLink.openPayment(response.paymentUrl, 'payment').then((opened) => {
            if (this.isActiveRoute(routeTicket) && !opened) {
              this.error.set('Банк вернул недопустимую ссылку оплаты. Переход отменен.');
            }
          }).catch(() => {
            if (this.isActiveRoute(routeTicket)) {
              this.error.set('Не удалось открыть ссылку оплаты.');
            }
          });
          return;
        }
        this.message.set('Банк не вернул ссылку на оплату.');
      },
      error: (error) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.error.set(this.errorMessage(error, 'Не удалось перейти к оплате.'));
        this.submitting.set(false);
      }
    });
  }

  reportPaid(): void {
    if (!this.canReportPaid()) {
      return;
    }
    const routeTicket = this.captureRoute();
    const token = this.token();
    if (!routeTicket || !token) {
      return;
    }
    this.reportingPaid.set(true);
    this.error.set('');
    this.message.set('');
    this.api.reportPublicCommonInvoicePaid(token).subscribe({
      next: (invoice) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.invoice.set(invoice);
        this.reportingPaid.set(false);
        this.message.set('Сообщение об оплате принято. Поступление ещё будет проверено.');
      },
      error: (error) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.reportingPaid.set(false);
        this.error.set(this.errorMessage(error, 'Не удалось сообщить об оплате. Обновите счет и попробуйте снова.'));
      }
    });
  }

  async copyPaymentValue(value?: string | null): Promise<void> {
    const clean = value?.trim();
    if (!clean) {
      return;
    }
    const routeTicket = this.captureRoute();
    if (!routeTicket) {
      return;
    }
    try {
      await navigator.clipboard.writeText(clean);
      if (this.isActiveRoute(routeTicket)) {
        this.message.set('Скопировано.');
      }
    } catch {
      if (this.isActiveRoute(routeTicket)) {
        this.error.set('Не удалось скопировать. Выделите реквизиты вручную.');
      }
    }
  }

  openManualPaymentUrl(): void {
    const target = this.manualPaymentUrl();
    const routeTicket = this.captureRoute();
    if (!target || !routeTicket) {
      this.error.set('Ссылка оплаты не настроена. Обратитесь к менеджеру.');
      return;
    }
    void this.externalLink.openPayment(target, 'manual').then((opened) => {
      if (this.isActiveRoute(routeTicket) && !opened) {
        this.error.set('Ссылка оплаты имеет недопустимый формат. Переход отменен.');
      }
    }).catch(() => {
      if (this.isActiveRoute(routeTicket)) {
        this.error.set('Не удалось открыть ссылку оплаты.');
      }
    });
  }

  formatRubles(amount?: number | null): string {
    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(amount ?? 0);
  }

  loadInvoice(): void {
    const token = this.token();
    const routeTicket = this.captureRoute();
    if (!token || !routeTicket) {
      this.loading.set(false);
      this.error.set('Общая платежная ссылка не найдена.');
      return;
    }
    this.invoiceLoadSubscription?.unsubscribe();
    this.invoiceLoadSubscription = undefined;
    this.loading.set(true);
    const subscription = this.api.getPublicCommonInvoice(token).subscribe({
      next: (invoice) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.invoice.set(invoice);
        this.loading.set(false);
      },
      error: (error) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.error.set(this.errorMessage(error, 'Не удалось открыть общий счет.'));
        this.loading.set(false);
      }
    });
    if (!subscription.closed && this.isActiveRoute(routeTicket)) {
      this.invoiceLoadSubscription = subscription;
    }
  }

  private refreshAfterReturn(): void {
    const current = this.invoice();
    const now = Date.now();
    if (now - this.lastReturnRefreshAt < 1200 || this.loading() || this.refreshing() || !this.token() || !current || ['PAID', 'UNPAID', 'DISABLED'].includes(current.status)) {
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
    this.invoiceLoadSubscription?.unsubscribe();
    this.invoiceLoadSubscription = undefined;
    const subscription = this.api.getPublicCommonInvoice(token).subscribe({
      next: (invoice) => {
        if (!this.isActiveRoute(routeTicket)) {
          return;
        }
        this.invoice.set(invoice);
        this.refreshing.set(false);
      },
      error: () => {
        if (this.isActiveRoute(routeTicket)) {
          this.refreshing.set(false);
        }
      }
    });
    if (!subscription.closed && this.isActiveRoute(routeTicket)) {
      this.invoiceLoadSubscription = subscription;
    }
  }

  private activateInvoiceRoute(rawToken: string | null): void {
    const token = rawToken?.trim() ?? '';
    const routeKey = token ? `pay-group:${token}` : 'pay-group:invalid';
    if (!this.routeEpoch.change(routeKey)) {
      return;
    }

    this.cancelRouteRead();
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
    this.reportingPaid.set(false);
    this.error.set('');
    this.message.set('');
    this.email.set('');
    this.offerConsent.set(false);
    this.privacyConsent.set(false);
    this.receiptConsent.set(false);
    this.lastReturnRefreshAt = 0;
  }

  private cancelRouteRead(): void {
    const subscription = this.invoiceLoadSubscription;
    this.invoiceLoadSubscription = undefined;
    subscription?.unsubscribe();
    this.loading.set(false);
    this.refreshing.set(false);
  }

  private captureRoute(): RouteEpochTicket | null {
    return this.routeEpoch.capture();
  }

  private isActiveRoute(routeTicket: RouteEpochTicket): boolean {
    return this.routeEpoch.accepts(routeTicket);
  }

  private statusText(status?: string): string {
    return {
      COLLECTING: 'Собирается',
      READY: 'Готов к счету',
      INVOICED: 'Выставлен',
      REMINDER: 'Напоминание',
      PARTIALLY_PAID: 'Частично оплачен',
      NEEDS_ATTENTION: 'Требует проверки',
      PAID: 'Оплачен',
      UNPAID: 'Не оплачен',
      BAN: 'Бан',
      DISABLED: 'Отключен'
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
