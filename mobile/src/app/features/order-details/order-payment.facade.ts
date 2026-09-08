import { signal, type WritableSignal } from '@angular/core';
import { finalize, firstValueFrom, Subscription } from 'rxjs';
import type { ToastOptions } from '@ionic/core';
import type { ToastController } from '@ionic/angular/standalone';
import type { ManagerPaymentLinkResponse, PaymentRouteChangeContext, PaymentRouteChangeTarget, TbankPaymentStatus } from '../../core/api.service';
import type { OrderPaymentApi } from '../../core/order-payment.api';
import { EditorSession, type EditorTicket } from '../../core/editor-session';
import type { RouteEpochTicket } from '../../core/route-epoch.guard';
import type { PageWriteTracker } from '../../core/page-write-tracker';
import type { MobileConfirmService } from '../../shared/mobile-confirm.service';

type Dependencies = {
  writes: Pick<PageWriteTracker, 'track'>;
  api: Pick<OrderPaymentApi, 'getTbankStatus' | 'createManagerOrderPaymentLink' | 'getManagerOrderPaymentRouteChangeContext' | 'changeManagerOrderPaymentRoute' | 'markManagerOrderPaperInvoiceIssued'>;
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
  private statusRead?: Subscription;

  constructor(private readonly deps: Dependencies) {}

  canShowPaymentLinkAction(): boolean {
    return this.deps.hasDetails() && this.deps.canManage() && !!this.tbankStatus()?.managerUiEnabled && !!this.tbankStatus()?.paymentLinksEnabled;
  }
  paymentLinkModeLabel(): string { return 'Банковский счёт'; }
  canManagePaymentRoute(): boolean { return this.deps.hasDetails() && this.deps.canManage(); }
  paymentRouteTargetActive(target: PaymentRouteChangeTarget): boolean {
    return target !== 'OWNER_TBANK' && this.paymentRouteContext()?.configuredMode === target;
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
    this.session.close(); this.paymentRouteVisible.set(false); this.paymentRouteContextLoading.set(false);
    this.paymentRouteContext.set(null); this.paymentRouteChanging.set(false);
  }

  async changePaymentRoute(target: PaymentRouteChangeTarget): Promise<void> {
    const orderId = this.deps.orderId(); const route = this.deps.capture(); const ticket = this.session.capture();
    const current = this.paymentRouteContext();
    if (!orderId || !route || !ticket || !current?.canChange || this.pendingRoutes.has(orderId) || this.paymentRouteTargetActive(target)) return;
    const context = { ...current };
    this.pendingRoutes.add(orderId); this.paymentRouteChanging.set(true);
    try {
      const targetLabel = this.paymentRouteTargetLabel(target);
      const confirmed = await this.deps.confirm({ title: 'Сменить получателя оплаты',
        message: `Переключить заказ на «${targetLabel}»? Продолжайте только если клиент ещё не оплатил: прежний маршрут будет закрыт, а клиенту запланировано обновлённое сообщение.`, confirmText: 'Сменить', danger: true });
      if (!confirmed || !this.accepts(ticket, route)) return;
      this.deps.error.set(null);
      const response = await firstValueFrom(this.deps.writes.track(this.deps.api.changeManagerOrderPaymentRoute(orderId, {
        expectedPaymentLinkId: context.paymentLinkId, target, confirmedUnpaid: true,
        expectedTargetPaymentProfileId: target === 'OWNER_TBANK' ? context.expectedTargetPaymentProfileId ?? null : null
      })));
      if (!this.accepts(ticket, route)) return;
      this.paymentLink.set(null); this.paymentRouteVisible.set(false); this.paymentRouteContext.set(null); this.deps.reload();
      await this.presentPaymentRouteSuccessToast(response.clientNotificationScheduled ? 'Получатель изменён. Сообщение клиенту запланировано.' : 'Получатель изменён.', route);
    } catch (error) {
      if (this.accepts(ticket, route)) this.deps.error.set(this.deps.errorMessage(error, 'Не удалось сменить получателя оплаты'));
    } finally {
      this.pendingRoutes.delete(orderId);
      if (this.accepts(ticket, route)) this.paymentRouteChanging.set(false);
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

        return 'Реквизиты сотрудника';

      case 'OWNER_PAPER_INVOICE':

        return 'Бумажный счёт владельца';

      default:

        return 'Банковская ссылка владельца';

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
