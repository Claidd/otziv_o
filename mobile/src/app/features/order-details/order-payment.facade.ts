import { signal, type WritableSignal } from '@angular/core';
import { finalize, firstValueFrom, Subscription } from 'rxjs';
import type { ToastOptions } from '@ionic/core';
import type { ToastController } from '@ionic/angular';
import type { ManagerPaymentLinkResponse, PaymentRouteChangeContext, PaymentRouteChangeTarget, TbankPaymentStatus } from '../../core/api.service';
import type { OrderPaymentApi } from '../../core/order-payment.api';
import { EditorSession, type EditorTicket } from '../../core/editor-session';
import type { RouteEpochTicket } from '../../core/route-epoch.guard';
import type { PageWriteTracker } from '../../core/page-write-tracker';
import type { MobileConfirmService } from '../../shared/mobile-confirm.service';
import {
  mobilePaymentRecipientAutoHint,
  mobilePaymentRecipientOptions,
  mobilePaymentRecipientOwnerCard,
  type MobilePaymentRecipientChoice,
  type MobilePaymentRecipientIssueOutcome,
  type MobilePaymentRecipientLoad,
  type MobilePaymentRecipientPickerRequest,
  type MobilePaymentRecipientPickerResult
} from '../../shared/payment-recipient-picker';

type Dependencies = {
  writes: Pick<PageWriteTracker, 'track'>;
  api: Pick<OrderPaymentApi, 'getTbankStatus' | 'createManagerOrderPaymentLink' | 'getManagerOrderPaymentRouteChangeContext' | 'getManagerOrderPaymentRouteRecipients' | 'changeManagerOrderPaymentRoute' | 'markManagerOrderPaperInvoiceIssued'>;
  /** Opens the «Выдать номер карты» window; resolves with null when it is closed without a card number. */
  pickRecipient(request: MobilePaymentRecipientPickerRequest): Promise<MobilePaymentRecipientPickerResult | null>;
  orderId(): number | null;
  hasDetails(): boolean;
  canManage(): boolean;
  capture(): RouteEpochTicket | null;
  accepts(ticket: RouteEpochTicket): boolean;
  confirm: MobileConfirmService['confirm'];
  toast(options: ToastOptions): ReturnType<ToastController['create']>;
  copy(value: string, ticket: RouteEpochTicket): Promise<boolean>;
  reload(): void;
  error: WritableSignal<string | null>;
  mutationKey: WritableSignal<string | null>;
  errorMessage(error: unknown, fallback: string): string;
};

export class OrderPaymentFacade {
  readonly tbankStatus = signal<TbankPaymentStatus | null>(null);
  readonly paymentLink = signal<ManagerPaymentLinkResponse | null>(null);
  readonly paymentRouteVisible = signal(false);
  readonly paymentRouteContextLoading = signal(false);
  readonly paymentRouteChanging = signal(false);
  readonly paymentRouteContext = signal<PaymentRouteChangeContext | null>(null);
  private readonly session = new EditorSession();
  private readonly pendingRoutes = new Set<number>();
  private readonly pendingLinks = new Set<number>();
  /** Closes the «Выдать номер карты» window when the order screen is left. */
  private recipientPickerLeave?: AbortController;
  private statusRead?: Subscription;

  constructor(private readonly deps: Dependencies) {}

  canShowPaymentLinkAction(): boolean {
    return this.deps.hasDetails() && this.deps.canManage() && !!this.tbankStatus()?.managerUiEnabled && !!this.tbankStatus()?.paymentLinksEnabled;
  }
  paymentLinkModeLabel(): string { return 'Банковский счёт'; }
  canManagePaymentRoute(): boolean { return this.deps.hasDetails() && this.deps.canManage(); }
  /**
   * One «Выдать номер карты» action replaces «реквизиты без ссылки» and «сменить получателя»:
   * from a bank link any transfer number fits, from a number the client wants another person.
   */
  cardNumberTarget(context: PaymentRouteChangeContext): PaymentRouteChangeTarget {
    if (context.paymentLinkId == null) return 'EMPLOYEE_REQUISITES';
    const bankLink = context.bankRecipientChangeAvailable === true
      || context.configuredMode === 'OWNER_TBANK'
      || (context.currentRoute ?? '').trim().startsWith('Эквайринг');
    return bankLink ? 'EMPLOYEE_REQUISITES' : 'DIFFERENT_RECIPIENT';
  }
  /**
   * A card number for an issued invoice is given from the window: the system's own choice or a person
   * the manager names. An order without an issued invoice and a withdrawn paper invoice keep the confirmation.
   */
  cardNumberOpensWindow(target: PaymentRouteChangeTarget, context: PaymentRouteChangeContext): boolean {
    return (target === 'EMPLOYEE_REQUISITES' || target === 'DIFFERENT_RECIPIENT')
      && context.paymentLinkId != null && !context.paperInvoiceIssued;
  }
  /** What «Выдать номер карты» does for this order, said under the button. */
  cardNumberHint(context: PaymentRouteChangeContext): string {
    const target = this.cardNumberTarget(context);
    if (this.cardNumberOpensWindow(target, context)) {
      return (target === 'EMPLOYEE_REQUISITES' ? 'Ссылка → номер карты. ' : 'Номер карты другого человека. ')
        + 'Откроется окно: система подберёт получателя сама, или выберите сотрудника — можно и сверх резерва';
    }
    if (context.paymentLinkId == null) return 'Счёт ещё не выставлен: получателя выберет система, сменить его можно сразу после выставления';
    return target === 'EMPLOYEE_REQUISITES' ? 'Ссылка → номер карты, получатель может остаться прежним' : 'Номер карты другого человека, нынешний исключается';
  }
  /**
   * The mode that is already set is not offered again. «Выдать номер карты» for an issued invoice is:
   * its window is where another person is named, as on the website.
   */
  paymentRouteTargetActive(target: PaymentRouteChangeTarget): boolean {
    const context = this.paymentRouteContext();
    return !!context && target !== 'OWNER_TBANK' && context.configuredMode === target && !this.cardNumberOpensWindow(target, context);
  }
  copyCurrentPaymentLink(link: ManagerPaymentLinkResponse): void {
    const ticket = this.deps.capture();
    if (ticket) void this.deps.copy(link.copyText || link.url, ticket);
  }

  async openPaymentRouteChange(): Promise<void> {
    const orderId = this.deps.orderId(); const route = this.deps.capture();
    if (!orderId || !route || !this.canManagePaymentRoute() || this.paymentRouteContextLoading() || this.pendingRoutes.has(orderId)) return;
    this.session.open(orderId);
    const ticket = this.session.beginRead('context')!;
    this.paymentRouteVisible.set(true); this.paymentRouteContextLoading.set(true); this.paymentRouteContext.set(null); this.deps.error.set(null);
    try {
      const context = await this.session.read(ticket, this.deps.api.getManagerOrderPaymentRouteChangeContext(orderId));
      if (context && this.accepts(ticket, route)) this.paymentRouteContext.set(context);
    } catch (error) {
      if (this.accepts(ticket, route)) {
        this.deps.error.set(this.deps.errorMessage(error, 'Не удалось проверить текущего получателя оплаты'));
        this.paymentRouteVisible.set(false);
      }
    } finally { if (this.accepts(ticket, route)) this.paymentRouteContextLoading.set(false); }
  }

  closePaymentRouteChange(): void {
    if (this.paymentRouteChanging()) return;
    this.deactivate();
  }
  deactivate(): void {
    this.recipientPickerLeave?.abort(); this.recipientPickerLeave = undefined;
    this.session.close(); this.paymentRouteVisible.set(false); this.paymentRouteContextLoading.set(false);
    this.paymentRouteContext.set(null); this.paymentRouteChanging.set(false);
  }

  async changePaymentRoute(target: PaymentRouteChangeTarget): Promise<void> {
    const orderId = this.deps.orderId(); const route = this.deps.capture(); const ticket = this.session.capture();
    const current = this.paymentRouteContext();
    if (!orderId || !route || !ticket || !current || (!current.canChange && !(target !== 'OWNER_TBANK' && current.bankRecipientChangeAvailable)) || this.pendingRoutes.has(orderId) || this.paymentRouteTargetActive(target)) return;
    const context = { ...current };
    this.pendingRoutes.add(orderId); this.paymentRouteChanging.set(true);
    try {
      if (this.cardNumberOpensWindow(target, context)) {
        await this.issueCardNumber(orderId, target, context, ticket, route);
        return;
      }
      const targetLabel = this.paymentRouteTargetLabel(target);
      const confirmed = (target === 'DIFFERENT_RECIPIENT' && !context.paperInvoiceIssued) || await this.deps.confirm({ title: 'Сменить получателя оплаты',
        message: (context.paperInvoiceIssued ? 'Подтвердите, что выписка проверена, денег нет и клиент уведомлён об отзыве бумажного счёта. ' : '') + `Переключить заказ на «${targetLabel}»? Продолжайте только если клиент ещё не оплатил: прежний маршрут будет закрыт, а клиенту запланировано обновлённое сообщение.`, confirmText: 'Сменить', danger: true });
      if (!confirmed || !this.accepts(ticket, route)) return;
      this.deps.error.set(null);
      const response = await firstValueFrom(this.deps.writes.track(this.deps.api.changeManagerOrderPaymentRoute(orderId, {
        expectedPaymentLinkId: context.paymentLinkId, target, confirmedUnpaid: true,
        ...(context.paperInvoiceIssued ? { paperInvoiceWithdrawn: true } : {}),
        expectedTargetPaymentProfileId: target === 'OWNER_TBANK' ? context.expectedTargetPaymentProfileId ?? null : null
      })));
      if (!this.accepts(ticket, route)) return;
      this.paymentLink.set(null); this.paymentRouteVisible.set(false); this.paymentRouteContext.set(null); this.deps.reload();
      await this.presentPaymentRouteSuccessToast(response.clientNotificationScheduled ? 'Получатель изменён. Сообщение клиенту запланировано.' : 'Получатель изменён.', route);
    } catch (error) {
      if (this.accepts(ticket, route)) {
        const message = this.deps.errorMessage(error, 'Не удалось сменить получателя оплаты');
        if ((error as { status?: number } | null)?.status === 423) {
          this.paymentRouteVisible.set(false);
          await this.presentPaymentRouteSuccessToast(message, route);
        } else this.deps.error.set(message);
      }
    } finally {
      this.pendingRoutes.delete(orderId);
      if (this.accepts(ticket, route)) this.paymentRouteChanging.set(false);
    }
  }

  /** The «Выдать номер карты» window of the order: the people, the choice and what the server answered. */
  private async issueCardNumber(orderId: number, target: PaymentRouteChangeTarget, context: PaymentRouteChangeContext, ticket: EditorTicket, route: RouteEpochTicket): Promise<void> {
    const leave = new AbortController(); this.recipientPickerLeave = leave;
    try {
      const outcome = await this.deps.pickRecipient({
        heading: `Заказ №${orderId}`, amountKopecks: null, currentRecipient: context.currentRecipient?.trim() ?? '',
        autoHint: mobilePaymentRecipientAutoHint(target), autoHintWithOwnerCard: mobilePaymentRecipientAutoHint(target, true), signal: leave.signal,
        loadRecipients: () => this.loadRecipientPickerOptions(orderId),
        issue: choice => this.submitRecipientChoice(orderId, target, context.paymentLinkId, choice, ticket, route)
      });
      if (!outcome || !this.accepts(ticket, route)) return;
      await this.presentPaymentRouteSuccessToast(outcome.status === 'accepted' ? outcome.message
        : `Способ оплаты изменён. Клиенту отправляется новый счёт с номером карты по заказу #${orderId}`, route);
    } finally { if (this.recipientPickerLeave === leave) this.recipientPickerLeave = undefined; }
  }

  private async loadRecipientPickerOptions(orderId: number): Promise<MobilePaymentRecipientLoad> {
    const read = this.session.beginRead('recipients');
    try {
      const response = read ? await this.session.read(read, this.deps.api.getManagerOrderPaymentRouteRecipients(orderId)) : undefined;
      return { amountKopecks: response?.amountKopecks ?? null, recipients: mobilePaymentRecipientOptions(response?.recipients), ownerCard: mobilePaymentRecipientOwnerCard(response?.ownerCard) };
    } catch (error) { throw new Error(this.deps.errorMessage(error, 'Не удалось загрузить сотрудников')); }
  }

  /** Issues the card number: the system's own choice, or the person the manager named. */
  private async submitRecipientChoice(orderId: number, target: PaymentRouteChangeTarget, paymentLinkId: number | null, choice: MobilePaymentRecipientChoice, ticket: EditorTicket, route: RouteEpochTicket): Promise<MobilePaymentRecipientIssueOutcome> {
    if (!this.accepts(ticket, route)) return { status: 'left' };
    this.deps.error.set(null);
    try {
      await firstValueFrom(this.deps.writes.track(this.deps.api.changeManagerOrderPaymentRoute(orderId, {
        expectedPaymentLinkId: paymentLinkId, target, confirmedUnpaid: true, expectedTargetPaymentProfileId: null,
        ...(choice.profileId != null ? { recipientProfileId: choice.profileId } : {})
      })));
      if (!this.accepts(ticket, route)) return { status: 'left' };
      this.paymentLink.set(null); this.paymentRouteVisible.set(false); this.paymentRouteContext.set(null); this.deps.reload();
      return { status: 'issued' };
    } catch (error) {
      if (!this.accepts(ticket, route)) return { status: 'left' };
      if ((error as { status?: number } | null)?.status === 423) {
        // The bank has to close the old link first; the request is saved and will finish by itself.
        this.paymentRouteVisible.set(false);
        return { status: 'accepted', message: this.deps.errorMessage(error, 'Запрос принят. Получатель сменится, когда банк подтвердит закрытие прежней ссылки.') };
      }
      // The window stays open with the refusal, so another person can be chosen at once.
      return { status: 'refused', message: this.deps.errorMessage(error, 'Не удалось выдать номер карты') };
    }
  }

  async markPaperInvoiceIssued(): Promise<void> {
    const orderId = this.deps.orderId(); const route = this.deps.capture(); const ticket = this.session.capture();
    const context = this.paymentRouteContext();
    if (!orderId || !route || !ticket || !context || context.configuredMode !== 'OWNER_PAPER_INVOICE' || context.paymentLinkId == null || context.paperInvoiceIssued || this.pendingRoutes.has(orderId)) return;
    this.pendingRoutes.add(orderId); this.paymentRouteChanging.set(true); this.deps.error.set(null);
    try {
      await firstValueFrom(this.deps.writes.track(this.deps.api.markManagerOrderPaperInvoiceIssued(orderId)));
      if (!this.accepts(ticket, route)) return;
      this.paymentRouteContext.update(current => current ? { ...current, paperInvoiceIssued: true } : current);
      await this.presentPaymentRouteSuccessToast('Отправка счёта отмечена. Автонапоминания могут продолжить работу.', route);
    } catch (error) {
      if (this.accepts(ticket, route)) this.deps.error.set(this.deps.errorMessage(error, 'Не удалось отметить отправку счёта'));
    } finally { this.pendingRoutes.delete(orderId); if (this.accepts(ticket, route)) this.paymentRouteChanging.set(false); }
  }

  createPaymentLink(): void {
    const orderId = this.deps.orderId(); const ticket = this.deps.capture();
    if (!orderId || !ticket || !this.canShowPaymentLinkAction() || this.pendingLinks.has(orderId)) return;
    this.pendingLinks.add(orderId); this.deps.mutationKey.set('payment-link'); this.deps.error.set(null);
    this.deps.writes.track(this.deps.api.createManagerOrderPaymentLink(orderId)).pipe(finalize(() => {
      this.pendingLinks.delete(orderId);
      if (this.deps.accepts(ticket)) this.deps.mutationKey.set(null);
    })).subscribe({
      next: response => { if (this.deps.accepts(ticket)) { this.paymentLink.set(response); void this.deps.copy(response.copyText || response.url, ticket); } },
      error: error => { if (this.deps.accepts(ticket)) this.deps.error.set(this.deps.errorMessage(error, 'Не удалось создать ссылку на оплату')); }
    });
  }

  loadTbankStatus(): void {
    if (!this.deps.canManage() || this.statusRead) return;
    const read = this.deps.api.getTbankStatus().pipe(finalize(() => { this.statusRead = undefined; })).subscribe({
      next: status => this.tbankStatus.set(status), error: () => this.tbankStatus.set(null)
    });
    if (!read.closed) this.statusRead = read;
  }
  stopStatusRead(): void { this.statusRead?.unsubscribe(); this.statusRead = undefined; }
  private accepts(ticket: EditorTicket, route: RouteEpochTicket): boolean { return this.session.accepts(ticket) && this.deps.accepts(route); }

  private paymentRouteTargetLabel(target: PaymentRouteChangeTarget): string {

    switch (target) {

      case 'EMPLOYEE_REQUISITES':

        return 'Номер карты вместо ссылки';
      case 'DIFFERENT_RECIPIENT':
        return 'Номер карты другого получателя';

      case 'OWNER_PAPER_INVOICE':

        return 'Бумажный счёт владельца';

      default:

        return 'Ссылка банка владельца';

    }

  }

  private async presentPaymentRouteSuccessToast(message: string, routeTicket: RouteEpochTicket): Promise<void> {

    const toast = await this.deps.toast({

      message,

      duration: 3500,

      position: 'top',

      color: 'success',

      icon: 'checkmark-circle'

    });

    if (this.deps.accepts(routeTicket)) {

      await toast.present();

    }

  }
}
