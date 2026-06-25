import { Component, OnInit, computed, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { IonContent, IonRefresher, IonRefresherContent, RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  ApiService,
  CommonInvoiceDetailsResponse,
  CommonInvoiceOrderResponse,
  CommonInvoiceSummaryResponse,
  OrderItem
} from '../core/api.service';
import { displayPhone, normalizePhoneDigits, phoneHref } from '../shared/phone-format';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileOrderCardComponent } from '../shared/mobile-order-card.component';

type InvoiceAction =
  | 'send'
  | 'remind'
  | 'paid'
  | 'unpaid'
  | 'ban'
  | 'retry'
  | 'resolve'
  | 'late-payment'
  | 'final-cancel-check'
  | 'payment-init-check'
  | 'approve-review-orders';

@Component({
  selector: 'app-common-billing-mobile',
  imports: [IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent, MobileOrderCardComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Общий счет" />

      <ion-content fullscreen>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <main class="common-billing-page">
          @if (loading() && !details()) {
            <p class="state-card">Загружаю общий счет...</p>
          }

          @if (error()) {
            <button class="state-card state-card--error" type="button" (click)="reload()">
              <span class="material-icons-sharp">error</span>
              {{ error() }}
            </button>
          }

          @if (summary(); as invoice) {
            <section class="invoice-hero">
              <div>
                <small>{{ statusLabel(invoice.status) }}</small>
                <h1>{{ invoice.title || invoice.accountName || 'Общий счет' }}</h1>
              </div>
              <button type="button" class="icon-button" (click)="reload()" [disabled]="loading()" aria-label="Обновить">
                <span class="material-icons-sharp">refresh</span>
              </button>
            </section>

            <section class="invoice-stats" aria-label="Сводка общего счета">
              <article>
                <span>Сумма</span>
                <strong>{{ kopecks(invoice.amountKopecks) }}</strong>
              </article>
              <article>
                <span>Оплачено</span>
                <strong>{{ kopecks(invoice.paidKopecks) }}</strong>
              </article>
              <article>
                <span>Остаток</span>
                <strong>{{ kopecks(invoice.remainingKopecks) }}</strong>
              </article>
              <article>
                <span>Готово</span>
                <strong>{{ invoice.readyOrders }}/{{ invoice.totalOrders }}</strong>
              </article>
            </section>

            <section class="invoice-actions" aria-label="Действия общего счета">
              @if (invoice.status === 'NEEDS_ATTENTION') {
                <button type="button" (click)="runInvoiceAction('retry')" [disabled]="!!mutating()">Повторить</button>
                <button type="button" (click)="runInvoiceAction('resolve')" [disabled]="!!mutating()">Закрыть проверку</button>
                <button type="button" (click)="runInvoiceAction('late-payment')" [disabled]="!!mutating()">Поздняя оплата</button>
                <button type="button" (click)="runInvoiceAction('final-cancel-check')" [disabled]="!!mutating()">Сверка отмены</button>
                <button type="button" (click)="runInvoiceAction('payment-init-check')" [disabled]="!!mutating()">Сверка платежа</button>
              } @else {
                <button type="button" (click)="runInvoiceAction('send')" [disabled]="!!mutating() || !canSendInvoice(invoice)">Отправить</button>
                <button type="button" (click)="copyPublicUrl(invoice)" [disabled]="!invoice.publicUrl">Ссылка</button>
                <button type="button" (click)="runInvoiceAction('approve-review-orders')" [disabled]="!!mutating() || !canApproveReviewOrders()">Одобрить все</button>
                <button type="button" class="success" (click)="runInvoiceAction('paid')" [disabled]="!!mutating() || !canMarkInvoicePaid(invoice)">Оплачен</button>
                <button type="button" class="danger" (click)="runInvoiceAction('unpaid')" [disabled]="!!mutating() || !canMarkInvoiceUnpaid(invoice)">Не оплачен</button>
                <button type="button" (click)="runInvoiceAction('remind')" [disabled]="!!mutating() || !canRemindInvoice(invoice)">Напомнить</button>
                <button type="button" class="danger" (click)="runInvoiceAction('ban')" [disabled]="!!mutating() || !canMarkInvoiceBan(invoice)">Бан</button>
                @if (invoiceActionHint(invoice)) {
                  <p class="invoice-action-hint">{{ invoiceActionHint(invoice) }}</p>
                }
              }
            </section>

            @if (invoice.sentAt || invoice.lastReminderAt || invoice.nextReminderAt || invoice.lastError) {
              <section class="invoice-timeline">
                @if (invoice.sentAt) {
                  <span>Отправлен: {{ dateTime(invoice.sentAt) }}</span>
                }
                @if (invoice.lastReminderAt) {
                  <span>Напоминание: {{ dateTime(invoice.lastReminderAt) }}</span>
                }
                @if (invoice.nextReminderAt) {
                  <span>Следующее: {{ dateTime(invoice.nextReminderAt) }}</span>
                }
                @if (invoice.lastError) {
                  <span class="error-line">{{ invoice.lastError }}</span>
                }
              </section>
            }

            <section class="invoice-orders" aria-label="Заказы общего счета">
              <header>
                <span>Состав</span>
                <strong>{{ orders().length }}</strong>
              </header>

              @for (order of orderCards(); track order.id) {
                <div class="invoice-order-card">
                  <app-mobile-order-card
                    [order]="order"
                    [statusActions]="[]"
                    [copiedKey]="copiedKey()"
                    [mutationKey]="mutating()"
                    [title]="orderTitle(order)"
                    [titleHref]="orderChatUrl(order)"
                    [toneClass]="orderTone(order)"
                    [statusLabel]="order.status || '-'"
                    [amountLabel]="orderMoney(orderPayableSum(order))"
                    [phoneLabel]="orderPhone(order)"
                    [phoneHref]="orderChatUrl(order)"
                    [reviewHref]="orderReviewUrl(order)"
                    [filialHref]="orderFilialUrl(order)"
                    [phoneCopyKey]="'invoice-order-phone-' + order.id"
                    [reviewCopyKey]="'invoice-order-review-' + order.id"
                    [paymentCopyKey]="'invoice-order-payment-' + order.id"
                    [progress]="orderProgress(order)"
                    [cityLabel]="orderCity(order)"
                    [workerLabel]="workerLabel(order)"
                    [workerTitle]="order.workerUserFio || 'Исполнитель не назначен'"
                    [showNoteEditor]="false"
                    [canManageOrderStatuses]="false"
                    [canManageClientWaiting]="false"
                    [unchangedDays]="orderUnchangedDays(order)"
                    [unchangedAlert]="isOrderUnchangedAlert(order)"
                    (copyPhone)="copyOrderPhone(order)"
                    (copyText)="copyOrderText(order, $event)"
                    (details)="openOrderCard(order)"
                    (workerClick)="openOrderCard(order)"
                  />
                  @if (invoiceOrderForCard(order); as invoiceOrder) {
                    <div class="order-actions">
                      <button type="button" (click)="openOrder(invoiceOrder)">Открыть</button>
                      <button type="button" (click)="markOrderPaid(invoiceOrder)" [disabled]="!!mutating() || invoiceOrder.paid || summary()?.status === 'NEEDS_ATTENTION'">
                        Оплачен
                      </button>
                      <button type="button" class="danger" (click)="detachOrder(invoiceOrder)" [disabled]="!!mutating() || !invoiceOrder.detachable || summary()?.status === 'NEEDS_ATTENTION'">
                        Отключить
                      </button>
                    </div>
                  }
                </div>
              } @empty {
                <p class="state-card">В общем счете пока нет заказов.</p>
              }
            </section>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .common-billing-page {
      display: grid;
      gap: 0.7rem;
      min-height: 100%;
      padding: 0.7rem var(--otziv-page-padding-x, 0.68rem) calc(var(--otziv-tabbar-height, 4.2rem) + 1rem);
      background: var(--otziv-page-background);
    }

    .state-card,
    .invoice-hero,
    .invoice-stats,
    .invoice-actions,
    .invoice-timeline,
    .invoice-orders {
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.9rem;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 0.55rem 1.3rem rgba(132, 139, 200, 0.12);
    }

    .state-card {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.4rem;
      min-height: 3.2rem;
      margin: 0;
      padding: 0.75rem;
      color: var(--otziv-info);
      font: 900 0.82rem/1.2 var(--otziv-card-title-font);
    }

    .state-card--error {
      color: var(--otziv-danger);
    }

    .invoice-hero {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 2.55rem;
      align-items: center;
      gap: 0.6rem;
      padding: 0.8rem;
    }

    .invoice-hero small,
    .invoice-stats span,
    .invoice-timeline span,
    .invoice-orders small {
      color: var(--otziv-info);
      font: 900 0.66rem/1.2 var(--otziv-card-title-font);
    }

    .invoice-hero h1 {
      margin: 0.18rem 0 0;
      color: var(--otziv-dark);
      font: 1000 1.05rem/1.08 var(--otziv-card-title-font);
    }

    .icon-button {
      display: grid;
      min-width: 2.35rem;
      min-height: 2.35rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.8rem;
      color: var(--otziv-info);
      background: var(--otziv-muted-surface);
    }

    .invoice-stats {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem;
      padding: 0.65rem;
    }

    .invoice-stats article {
      display: grid;
      gap: 0.25rem;
      min-height: 4.1rem;
      border: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0.75rem;
      padding: 0.6rem;
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-tone-work-surface) 100%);
    }

    .invoice-stats strong {
      color: var(--otziv-dark);
      font: 1000 1rem/1 var(--otziv-card-title-font);
    }

    .invoice-actions,
    .order-actions {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.45rem;
      padding: 0.65rem;
    }

    .invoice-actions button,
    .order-actions button {
      min-height: 2.15rem;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.72rem/1 var(--otziv-card-title-font);
    }

    .invoice-actions .success,
    .order-actions button:not(.danger):nth-child(2) {
      color: var(--otziv-success);
      background: var(--otziv-tone-success-surface);
    }

    .invoice-actions .danger,
    .order-actions .danger {
      color: var(--otziv-danger);
      background: var(--otziv-tone-correction-surface);
    }

    .invoice-actions button:disabled,
    .order-actions button:disabled {
      opacity: 0.48;
    }

    .invoice-action-hint {
      grid-column: 1 / -1;
      margin: 0.05rem 0 0;
      color: var(--otziv-info);
      font: 800 0.66rem/1.25 var(--otziv-card-title-font);
      text-align: center;
    }

    .invoice-timeline {
      display: grid;
      gap: 0.36rem;
      padding: 0.72rem;
    }

    .invoice-timeline .error-line {
      color: var(--otziv-danger);
    }

    .invoice-orders {
      display: grid;
      gap: 0.55rem;
      padding: 0.7rem;
    }

    .invoice-orders header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      color: var(--otziv-dark);
      font: 1000 0.9rem/1 var(--otziv-card-title-font);
    }

    .invoice-order-card {
      display: grid;
      gap: 0.48rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.8rem;
      padding: 0.65rem;
      background: var(--otziv-white);
    }

    .invoice-orders strong,
    .invoice-orders b {
      color: var(--otziv-dark);
      font: 1000 0.86rem/1.15 var(--otziv-card-title-font);
    }

    .order-money,
    .order-flags {
      display: flex;
      flex-wrap: wrap;
      gap: 0.36rem;
    }

    .order-money span,
    .order-money b,
    .order-flags span {
      display: inline-flex;
      min-height: 1.7rem;
      align-items: center;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      padding: 0 0.55rem;
      background: var(--otziv-field-background);
      font: 900 0.68rem/1 var(--otziv-card-title-font);
    }

    .order-actions {
      grid-template-columns: repeat(3, minmax(0, 1fr));
      padding: 0;
    }

    .invoice-order-card app-mobile-order-card {
      display: block;
      min-height: 31rem;
    }
  `]
})
export class CommonBillingPage implements OnInit {
  readonly invoiceId = signal<number | null>(null);
  readonly details = signal<CommonInvoiceDetailsResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutating = signal<string | null>(null);
  readonly copiedKey = signal<string | null>(null);
  readonly summary = computed(() => this.details()?.summary ?? null);
  readonly orders = computed(() => this.details()?.orders ?? []);
  readonly orderCards = computed(() => this.details()?.orderCards ?? []);
  readonly reviewApprovalCount = computed(() => this.orders()
    .filter(order => order.orderStatus === 'В проверку' || order.orderStatus === 'На проверке')
    .length);

  constructor(
    private readonly api: ApiService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly confirm: MobileConfirmService
  ) {}

  ngOnInit(): void {
    const invoiceId = Number(this.route.snapshot.paramMap.get('invoiceId') || 0);
    this.invoiceId.set(Number.isFinite(invoiceId) && invoiceId > 0 ? invoiceId : null);
    void this.load();
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    event.target.complete();
  }

  reload(): void {
    void this.load();
  }

  async runInvoiceAction(action: InvoiceAction): Promise<void> {
    const invoiceId = this.invoiceId();
    if (!invoiceId || this.mutating()) {
      return;
    }

    const invoice = this.summary();
    if (invoice) {
      const hint = this.invoiceActionHint(invoice, action);
      if (hint) {
        this.error.set(hint);
        return;
      }
    }

    const confirmed = await this.confirm.confirm({ message: this.invoiceActionConfirm(action), danger: action === 'ban' || action === 'unpaid' });
    if (!confirmed) {
      return;
    }

    this.mutating.set(action);
    try {
      const details = await firstValueFrom(this.invoiceActionRequest(invoiceId, action));
      this.details.set(details);
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось обновить общий счет.'));
    } finally {
      this.mutating.set(null);
    }
  }

  async markOrderPaid(order: CommonInvoiceOrderResponse): Promise<void> {
    const invoiceId = this.invoiceId();
    if (!invoiceId || this.mutating()) {
      return;
    }

    const confirmed = await this.confirm.confirm({ message: `Отметить заказ #${order.orderId} оплаченным внутри общего счета?` });
    if (!confirmed) {
      return;
    }

    await this.runOrderMutation(`paid-${order.orderId}`, () => this.api.markCommonInvoiceOrderPaid(invoiceId, order.orderId));
  }

  async detachOrder(order: CommonInvoiceOrderResponse): Promise<void> {
    const invoiceId = this.invoiceId();
    if (!invoiceId || this.mutating()) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      message: `Отключить заказ #${order.orderId} от общего счета?`,
      danger: true
    });
    if (!confirmed) {
      return;
    }

    await this.runOrderMutation(`detach-${order.orderId}`, () => this.api.detachCommonInvoiceOrder(invoiceId, order.orderId));
  }

  openOrder(order: CommonInvoiceOrderResponse): void {
    void this.router.navigate(['/tabs/orders', order.companyId, order.orderId]);
  }

  openOrderCard(order: OrderItem): void {
    if (!order.companyId || !order.id) {
      return;
    }
    void this.router.navigate(['/tabs/orders', order.companyId, order.id]);
  }

  async copyPublicUrl(invoice: CommonInvoiceSummaryResponse): Promise<void> {
    const value = (invoice.publicUrl ?? '').trim();
    if (!value) {
      return;
    }
    try {
      await navigator.clipboard.writeText(value);
      this.error.set(null);
    } catch {
      this.error.set('Не удалось скопировать ссылку общего счета.');
    }
  }

  async copyOrderPhone(order: OrderItem): Promise<void> {
    await this.copyText(normalizePhoneDigits(order.companyTelephone), `invoice-order-phone-${order.id}`, 'Не удалось скопировать телефон.');
  }

  async copyOrderText(order: OrderItem, kind: 'review' | 'payment'): Promise<void> {
    if (kind === 'review') {
      await this.copyText(this.orderReviewUrl(order), `invoice-order-review-${order.id}`, 'Не удалось скопировать ссылку отзыва.');
      return;
    }
    await this.copyText(this.summary()?.publicUrl ?? '', `invoice-order-payment-${order.id}`, 'Не удалось скопировать ссылку общего счета.');
  }

  invoiceOrderForCard(order: OrderItem): CommonInvoiceOrderResponse | null {
    return this.orders().find((item) => item.orderId === order.id) ?? null;
  }

  orderTitle(order: OrderItem): string {
    return [order.companyTitle || 'Без компании', order.filialTitle || 'Без филиала'].filter(Boolean).join(' - ');
  }

  orderPayableSum(order: OrderItem): number {
    return order.totalSumWithBadReviews ?? order.sum ?? 0;
  }

  orderMoney(value?: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value ?? 0)} руб.`;
  }

  orderPhone(order: OrderItem): string {
    return displayPhone(order.companyTelephone);
  }

  orderChatUrl(order: OrderItem): string {
    const digits = normalizePhoneDigits(order.companyTelephone);
    return (order.telegramBotInviteUrl ?? '').trim()
      || (order.maxBotInviteUrl ?? '').trim()
      || (order.companyUrlChat ?? '').trim()
      || (digits ? `tel:+${digits}` : '');
  }

  orderReviewUrl(order: OrderItem): string {
    const detailsId = (order.orderDetailsId ?? '').trim();
    if (!detailsId) {
      return '';
    }
    const origin = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
      ? 'https://o-ogo.ru'
      : window.location.origin;
    return new URL(`/${detailsId}`, origin).toString();
  }

  orderFilialUrl(order: OrderItem): string {
    return (order.filialUrl ?? '').trim() || phoneHref(order.companyTelephone);
  }

  orderCity(order: OrderItem): string {
    return (order.filialCity ?? '').trim() || 'Город не указан';
  }

  orderProgress(order: OrderItem): number {
    if (!order.amount || !order.counter) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
  }

  orderUnchangedDays(order: OrderItem): number {
    return Math.max(0, order.dayToChangeStatusAgo ?? 0);
  }

  isOrderUnchangedAlert(order: OrderItem): boolean {
    return this.orderUnchangedDays(order) >= 2;
  }

  workerLabel(order: OrderItem): string {
    const fio = (order.workerUserFio ?? '').trim();
    if (!fio) {
      return '-';
    }
    const [first, second] = fio.split(/\s+/);
    return second ? `${first} ${second.charAt(0)}.` : first;
  }

  orderTone(order: OrderItem): string {
    switch (order.status) {
      case 'Новый':
      case 'На проверке':
      case 'Напоминание':
        return 'lead-card--new';
      case 'Коррекция':
      case 'Публикация':
        return 'lead-card--send';
      case 'Опубликовано':
      case 'Оплачено':
        return 'lead-card--work';
      default:
        return '';
    }
  }

  kopecks(value?: number | null): string {
    const rubles = Math.round((value ?? 0) / 100);
    return `${new Intl.NumberFormat('ru-RU').format(rubles)} ₽`;
  }

  dateTime(value?: string | null): string {
    if (!value) {
      return '-';
    }
    return new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(new Date(value));
  }

  statusLabel(status?: string | null): string {
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
      case 'PAID':
        return 'Оплачен';
      case 'UNPAID':
        return 'Не оплачен';
      case 'BAN':
        return 'Бан';
      case 'NEEDS_ATTENTION':
        return 'Требует внимания';
      case 'DISABLED':
        return 'Отключен';
      default:
        return status || 'Общий счет';
    }
  }

  canSendInvoice(invoice: CommonInvoiceSummaryResponse): boolean {
    return this.allOrdersReady(invoice) && ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status);
  }

  canRemindInvoice(invoice: CommonInvoiceSummaryResponse): boolean {
    return this.allOrdersReady(invoice) && ['INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status);
  }

  canMarkInvoicePaid(invoice: CommonInvoiceSummaryResponse): boolean {
    return ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'UNPAID'].includes(invoice.status)
      || (invoice.status === 'COLLECTING' && this.allOrdersReady(invoice));
  }

  canMarkInvoiceUnpaid(invoice: CommonInvoiceSummaryResponse): boolean {
    return invoice.totalOrders > 0
      && invoice.paidOrders < invoice.totalOrders
      && ['INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status);
  }

  canMarkInvoiceBan(invoice: CommonInvoiceSummaryResponse): boolean {
    return invoice.status === 'UNPAID';
  }

  canApproveReviewOrders(): boolean {
    return this.summary()?.status !== 'NEEDS_ATTENTION' && this.reviewApprovalCount() > 0;
  }

  invoiceActionHint(invoice: CommonInvoiceSummaryResponse, action?: InvoiceAction): string | null {
    if (invoice.status === 'NEEDS_ATTENTION') {
      return null;
    }
    if ((action === 'send' || action === 'remind') && !this.allOrdersReady(invoice)) {
      return `Сначала должны быть готовы все заказы: сейчас ${invoice.readyOrders}/${invoice.totalOrders}.`;
    }
    if (action === 'send' && !this.canSendInvoice(invoice)) {
      return 'Отправка доступна только для готового открытого общего счета.';
    }
    if (action === 'remind' && !this.canRemindInvoice(invoice)) {
      return 'Напоминание доступно только после выставления счета.';
    }
    if (action === 'paid' && !this.canMarkInvoicePaid(invoice)) {
      return invoice.status === 'READY' || invoice.status === 'COLLECTING'
        ? `Сначала должны быть готовы все заказы: сейчас ${invoice.readyOrders}/${invoice.totalOrders}.`
        : 'Этот общий счет сейчас нельзя отметить оплаченным.';
    }
    if (action === 'unpaid' && !this.canMarkInvoiceUnpaid(invoice)) {
      return 'В Не оплачен можно перевести только уже выставленный счет с неоплаченными заказами.';
    }
    if (action === 'ban' && !this.canMarkInvoiceBan(invoice)) {
      return 'Бан доступен только после статуса Не оплачен.';
    }
    if (action === 'approve-review-orders' && !this.canApproveReviewOrders()) {
      return 'В составе нет заказов в статусе "В проверку" или "На проверке".';
    }
    if (action === 'approve-review-orders') {
      return null;
    }
    if (!this.allOrdersReady(invoice)) {
      return `Сначала должны быть готовы все заказы: сейчас ${invoice.readyOrders}/${invoice.totalOrders}.`;
    }
    return null;
  }

  private allOrdersReady(invoice: CommonInvoiceSummaryResponse): boolean {
    return invoice.totalOrders > 0 && invoice.readyOrders >= invoice.totalOrders;
  }

  private async load(): Promise<void> {
    const invoiceId = this.invoiceId();
    if (!invoiceId) {
      this.error.set('Не указан ID общего счета.');
      return;
    }

    this.loading.set(true);
    try {
      this.details.set(await firstValueFrom(this.api.getCommonInvoice(invoiceId)));
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось загрузить общий счет.'));
    } finally {
      this.loading.set(false);
    }
  }

  private async runOrderMutation(
    key: string,
    request: () => ReturnType<ApiService['getCommonInvoice']>
  ): Promise<void> {
    this.mutating.set(key);
    try {
      this.details.set(await firstValueFrom(request()));
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось изменить заказ общего счета.'));
    } finally {
      this.mutating.set(null);
    }
  }

  private async copyText(value: string, key: string, fallback: string): Promise<void> {
    const text = value.trim();
    if (!text) {
      return;
    }

    try {
      await navigator.clipboard.writeText(text);
      this.copiedKey.set(key);
      window.setTimeout(() => {
        if (this.copiedKey() === key) {
          this.copiedKey.set(null);
        }
      }, 1200);
      this.error.set(null);
    } catch {
      this.error.set(fallback);
    }
  }

  private invoiceActionRequest(invoiceId: number, action: InvoiceAction): ReturnType<ApiService['getCommonInvoice']> {
    switch (action) {
      case 'send':
        return this.api.sendCommonInvoice(invoiceId);
      case 'remind':
        return this.api.remindCommonInvoice(invoiceId);
      case 'paid':
        return this.api.markCommonInvoicePaid(invoiceId);
      case 'unpaid':
        return this.api.markCommonInvoiceUnpaid(invoiceId);
      case 'ban':
        return this.api.markCommonInvoiceBan(invoiceId);
      case 'retry':
        return this.api.retryCommonInvoiceAttention(invoiceId);
      case 'resolve':
        return this.api.resolveCommonInvoiceAttention(invoiceId);
      case 'late-payment':
        return this.api.applyCommonInvoiceLatePayment(invoiceId);
      case 'final-cancel-check':
        return this.api.confirmCommonInvoiceFinalPaymentCancelCheck(invoiceId);
      case 'payment-init-check':
        return this.api.confirmCommonInvoicePaymentInitCheck(invoiceId);
      case 'approve-review-orders':
        return this.api.approveCommonInvoiceReviewOrders(invoiceId);
    }
  }

  private invoiceActionConfirm(action: InvoiceAction): string {
    switch (action) {
      case 'send':
        return 'Отправить общий счет клиенту?';
      case 'remind':
        return 'Отправить напоминание по общему счету?';
      case 'paid':
        return 'Отметить общий счет оплаченным?';
      case 'unpaid':
        return 'Отметить общий счет не оплаченным?';
      case 'ban':
        return 'Перевести общий счет в бан?';
      case 'retry':
        return 'Повторить обработку общего счета?';
      case 'resolve':
        return 'Закрыть ручную проверку общего счета?';
      case 'late-payment':
        return 'Распределить поздний платеж по заказам общего счета?';
      case 'final-cancel-check':
        return 'Подтвердить сверку отмены финального платежа?';
      case 'payment-init-check':
        return 'Подтвердить сверку создания платежа?';
      case 'approve-review-orders':
        return `Одобрить ${this.reviewApprovalCount()} заказ(ов) и перевести их в публикацию?`;
    }
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const body = error.error;
      if (typeof body === 'string') {
        const trimmed = body.trim();
        if (trimmed) {
          return trimmed;
        }
      }
      if (body && typeof body === 'object') {
        const message = this.readErrorField(body, 'message')
          || this.readErrorField(body, 'reason')
          || this.readErrorField(body, 'detail')
          || this.readErrorField(body, 'title')
          || this.readErrorField(body, 'error');
        if (message) {
          return message;
        }
      }
      if (error.status === 409) {
        return 'Действие сейчас недоступно для текущего статуса общего счета.';
      }
      return error.status ? `${fallback} Код ${error.status}.` : fallback;
    }
    if (typeof error === 'object' && error && 'message' in error) {
      return String((error as { message?: unknown }).message || fallback);
    }
    return fallback;
  }

  private readErrorField(body: object, key: string): string | null {
    const value = (body as Record<string, unknown>)[key];
    return typeof value === 'string' && value.trim() ? value.trim() : null;
  }
}
