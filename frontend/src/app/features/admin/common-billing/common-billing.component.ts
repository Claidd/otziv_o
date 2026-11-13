import { openPaperInvoiceDialog } from '../../../shared/paper-invoice-dialog.component';
import { PaperInvoiceListComponent } from '../../../shared/paper-invoice-list.component';
import { paperInvoiceWorkflow, type PaperInvoiceListState } from '../../../shared/paper-invoice-workflow';
import {
  ClientContractError, commonInvoiceDeliveryWait, commonInvoiceDeliveryWarning, deliveryOperationMessage, DeliveryStatusWatcher
} from '@otziv/client-common/billing-payments';
import { DatePipe, Location } from '@angular/common';
import { Component, ElementRef, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, firstValueFrom, switchMap } from 'rxjs';
import {
  CommonBillingAccountResponse,
  CommonBillingApi,
  CommonInvoiceDetailsResponse,
  CommonInvoiceOrderResponse,
  CommonInvoicePaymentRefResponse,
  CommonInvoicePaymentRouteChangeTarget,
  CommonInvoiceReturnableOrder,
  CommonInvoiceReturnableOrdersResponse,
  CommonInvoiceSummaryResponse,
  ManualPaymentConfirmationRequest
} from '../../../core/common-billing.api';
import {
  CommonManualPaymentAttributionApi,
  type CommonManualPaymentMode
} from '../../../core/common-manual-payment-attribution.api';
import { AuthService } from '../../../core/auth.service';
import { PaymentsApi } from '../../../core/payments.api';
import {
  CompanyCardItem,
  ManagerApi,
  OrderCardItem,
  type PaymentRouteChangeTarget
} from '../../../core/manager.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';
import { copyTextToClipboard } from '../../../shared/clipboard-copy';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { MobileBottomPagerComponent } from '../../../shared/mobile/mobile-bottom-pager.component';
import { ToastService } from '../../../shared/toast.service';
import {
  bankPaymentRouteLabel,
  isBankPaymentRouteType
} from '../../../shared/bank-payment-presentation';
import { ManagerBoardOrderFacade } from '../../manager/manager-board-order.facade';
import { ManagerOrderCardComponent } from '../../manager/manager-order-card.component';
import type { ManagerOrderEditDraftChange } from '../../manager/manager-order-edit-modal.component';
import { OrderPaymentRouteWindowsComponent } from '../../manager/order-payment-route-windows.component';
import { ManagerOrderEditModalComponent } from '../../manager/manager-order-edit-modal.component';
import { CommonManualPaymentAttributionModalComponent } from './common-manual-payment-attribution-modal.component';
import { CommonInvoiceOverdueTermComponent } from './common-invoice-overdue-term.component';
import { CommonInvoiceReturnOrdersComponent } from './common-invoice-return-orders.component';
import {
  PaymentRecipientPickerComponent,
  type PaymentRecipientChoice,
  type PaymentRecipientOption,
  type PaymentRecipientOwnerCard
} from '../../../shared/payment-recipient-picker.component';
import { CommonInvoiceGuideComponent, type CommonInvoiceGuideSection } from './common-invoice-guide.component';
import {
  StatusAction,
  managerErrorMessage,
  managerOrderActions,
  managerOrderReviewCopyText,
  managerPayableOrderSum
} from '../../manager/manager-board.config';

type AccountDraft = {
  name: string;
  enabled: boolean;
  autoRepeatOrders: boolean;
  managerId: string;
  invoiceCompanyId: string;
};

const PAYMENT_INIT_MANUAL_CHECK_PREFIXES = [
  'payment_init_stale',
  'payment_init_conflict',
  'payment_init_exception',
  'payment_init_response_mismatch',
  'payment_init_response_collision',
  'payment_init_invalid_url',
  'payment_cached_invalid_url',
  'tbank_init_failed'
] as const;

const MANUALLY_CONFIRMABLE_MIGRATION_PAYMENT_ERROR =
  'migration_common_payment_registry:nonterminal_or_unknown_payment_ref_on_invoice';

export const PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL = 'В банке оплаты нет — продолжить';
export const PAPER_INVOICE_ISSUED_ROLES = ['ADMIN', 'OWNER', 'MANAGER'] as const;

type CommonInvoicePaidEligibility = {
  status: string;
  totalOrders: number;
  readyOrders: number;
  invoicePaymentMode?: string | null;
  paperInvoiceIssuedAt?: string | null;
};

export function paperInvoiceRequiresDeliveryConfirmation(
  invoice: CommonInvoicePaidEligibility | null | undefined
): boolean {
  return Boolean(
    invoice?.invoicePaymentMode === 'OWNER_PAPER_INVOICE'
      && !invoice.paperInvoiceIssuedAt
  );
}

export function canMarkCommonInvoicePaid(
  invoice: CommonInvoicePaidEligibility | null | undefined
): boolean {
  if (!invoice || paperInvoiceRequiresDeliveryConfirmation(invoice)) {
    return false;
  }
  return ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'UNPAID'].includes(invoice.status)
    || (invoice.status === 'COLLECTING' && invoice.totalOrders > 0 && invoice.readyOrders >= invoice.totalOrders);
}

type CommonInvoiceReturnEligibility = {
  status: string;
  sentAt?: string | null;
  paymentRouteSelectedAt?: string | null;
  paperInvoiceIssuedAt?: string | null;
  invoicePaymentMode?: string | null;
  invoicePurpose?: string | null;
};

/**
 * Whether «Вернуть заказ в счёт» is offered: the invoice is still being collected, was not sent,
 * has no chosen recipient and is not a paper invoice. The server re-checks every return.
 */
export function canReturnOrdersToCommonInvoice(
  invoice: CommonInvoiceReturnEligibility | null | undefined
): boolean {
  return Boolean(
    invoice
      && (invoice.status === 'COLLECTING' || invoice.status === 'READY')
      && !invoice.sentAt
      && !invoice.paymentRouteSelectedAt
      && !invoice.paperInvoiceIssuedAt
      && invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
      && invoice.invoicePurpose !== 'BAD_REVIEW_SUCCESSOR'
  );
}

export function paymentInitNoPaymentInstructions(): string[] {
  return [
    'В кабинете банка, указанного в маршруте счёта, откройте операции и ищите платёж по OrderId.',
    'PaymentId — это отдельный идентификатор платежа. Не подставляйте его вместо OrderId: выберите поиск по PaymentId, если он доступен, или сверьте PaymentId в карточке операции, найденной по OrderId.',
    'Если найден активный платёж — не создавайте новую ссылку и дождитесь его итога.',
    'Если найден успешный платёж или операция, которой нужна отмена/возврат, — не нажимайте кнопку ниже. Обработайте операцию по её фактическому статусу в разделе платежей.',
    `Только если ни один платёж не активен, не успешен и не требует отмены/возврата, нажмите «${PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL}».`
  ];
}

export function paymentInitNoPaymentActionLabel(): string {
  return `«${PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL}» подтверждает только отсутствие оплаты `
    + 'по перечисленным OrderId и PaymentId в банке платежа и закрывает техническую ошибку. '
    + 'Счёт не будет отмечен оплаченным и не перейдёт в статус «Не оплачено». '
    + 'Если клиент оплатил переводом на обычную карту, после этого отдельным действием «Оплачено» '
    + 'зафиксируйте перевод и добавьте комментарий или чек.';
}

export function paymentInitNoPaymentConfirmation(evidenceText: string): string {
  return 'РЕЗУЛЬТАТ ПРОВЕРКИ БАНКА: ПО УКАЗАННЫМ ID ОПЛАТЫ НЕТ.\n\n'
    + 'Подтвердите, что вы проверили в банке платежа ВСЕ перечисленные OrderId и PaymentId и ни один платёж по ним '
    + 'не активен, не успешен и не требует отмены или возврата.\n\n'
    + 'Если такой платёж найден, нажмите «Отмена» и не продолжайте.\n\n'
    + 'Это действие означает ТОЛЬКО отсутствие оплаты по перечисленным банковским ID. '
    + 'Оно НЕ отмечает счёт оплаченным и НЕ переводит его в «Не оплачено», а только закрывает техническую ошибку.\n\n'
    + 'Если клиент оплатил переводом на обычную карту, после этого отдельным действием «Оплачено» '
    + 'зафиксируйте перевод и добавьте комментарий или чек.'
    + evidenceText;
}

export function isPaymentInitManualCheckError(error: string | null | undefined): boolean {
  const normalized = (error ?? '').trim().toLowerCase();
  return PAYMENT_INIT_MANUAL_CHECK_PREFIXES.some(prefix => normalized.startsWith(prefix))
    || normalized.startsWith(MANUALLY_CONFIRMABLE_MIGRATION_PAYMENT_ERROR);
}

export function isMigrationPaymentRegistryError(error: string | null | undefined): boolean {
  return (error ?? '').trim().toLowerCase().startsWith('migration_common_payment_registry:');
}

export interface CommonInvoicePaymentEvidenceItem {
  key: string;
  label: string;
  orderId: string;
  paymentId: string;
  amountKopecks: number | null;
  status: string;
  reason: string;
  terminalLabel: string;
  terminalKey: string;
}

export interface CommonInvoicePaymentEvidenceSnapshot {
  invoiceId: number;
  evidenceToken: string;
  evidence: CommonInvoicePaymentEvidenceItem[];
}

export function commonInvoicePaymentEvidence(
  invoice: Pick<
    CommonInvoiceSummaryResponse,
    | 'tbankOrderId'
    | 'tbankPaymentId'
    | 'tbankPaymentAmountKopecks'
    | 'tbankTerminalLabel'
    | 'tbankTerminalKey'
  > | null,
  refs: readonly CommonInvoicePaymentRefResponse[] | null | undefined,
  includeEmptyInvoiceBinding = false
): CommonInvoicePaymentEvidenceItem[] {
  const invoiceHasEvidence = Boolean(
    invoice?.tbankOrderId?.trim()
      || invoice?.tbankPaymentId?.trim()
      || invoice?.tbankPaymentAmountKopecks != null
      || invoice?.tbankTerminalLabel?.trim()
      || invoice?.tbankTerminalKey?.trim()
  );
  return [
    ...(includeEmptyInvoiceBinding || invoiceHasEvidence ? [{
      key: 'invoice',
      label: 'Счёт',
      orderId: invoice?.tbankOrderId?.trim() || 'не сохранён',
      paymentId: invoice?.tbankPaymentId?.trim() || 'не сохранён',
      amountKopecks: invoice?.tbankPaymentAmountKopecks ?? null,
      status: '',
      reason: '',
      terminalLabel: invoice?.tbankTerminalLabel?.trim() || 'не сохранён',
      terminalKey: invoice?.tbankTerminalKey?.trim() || 'не сохранён'
    }] : []),
    ...(refs ?? []).map(ref => ({
      key: `ref-${ref.id}`,
      label: `Реестр #${ref.id}`,
      orderId: ref.orderId?.trim() || 'не сохранён',
      paymentId: ref.paymentId?.trim() || 'не сохранён',
      amountKopecks: ref.amountKopecks ?? null,
      status: ref.status?.trim() || '',
      reason: ref.reason?.trim() || '',
      terminalLabel: ref.terminalLabel?.trim() || 'не сохранён',
      terminalKey: ref.terminalKey?.trim() || 'не сохранён'
    }))
  ];
}

export function commonInvoicePaymentEvidenceSnapshot(
  details: CommonInvoiceDetailsResponse | null | undefined,
  expectedInvoiceId: number | null | undefined
): CommonInvoicePaymentEvidenceSnapshot | null {
  const invoice = details?.summary;
  const evidenceToken = details?.paymentEvidenceToken?.trim() || '';
  if (!invoice
    || !expectedInvoiceId
    || invoice.id !== expectedInvoiceId
    || !isPaymentInitManualCheckError(invoice.lastError)
    || !evidenceToken) {
    return null;
  }
  return {
    invoiceId: invoice.id,
    evidenceToken,
    evidence: commonInvoicePaymentEvidence(
      invoice,
      details.paymentRefs,
      isMigrationPaymentRegistryError(invoice.lastError)
    )
  };
}

export function isIncompletePartiallyPaidInvoice(
  invoice: Pick<CommonInvoiceSummaryResponse, 'status' | 'readyOrders' | 'totalOrders'> | null
): boolean {
  return Boolean(
    invoice
      && invoice.status === 'PARTIALLY_PAID'
      && invoice.totalOrders > 0
      && invoice.readyOrders < invoice.totalOrders
  );
}

export function commonInvoiceStatusProblem(status: string | null | undefined, lastError?: string | null): string {
  // While a client message is being sent the invoice is «READY» again for that time (after a change of the
  // payment method too): that is the wait the page already shows, not an invoice stuck without sending.
  if (status === 'READY' && commonInvoiceDeliveryWait(lastError)) {
    return '';
  }
  switch (status) {
    case 'NEEDS_ATTENTION':
      return 'Счет требует ручного разбора.';
    case 'UNPAID':
      return 'Счет переведен в неоплаченные. Проверьте, что заказы обработаны корректно.';
    case 'BAN':
      return 'Счет в бане. Нужна проверка дальнейших действий по заказам.';
    case 'DISABLED':
      return 'Общий счет отключен. Проверьте, почему неоплаченные заказы остались в отключенной связке.';
    case 'READY':
      return 'Счет готов к отправке. Проверьте, не завис ли он без выставления клиенту.';
    default:
      return '';
  }
}

/**
 * The invoice's problem code for the «Проблема общего счета» block. The marker of a client message that is still
 * in the queue is not a problem: the block stays closed and the page shows the wait line instead.
 */
export function commonInvoiceProblemCode(lastError: string | null | undefined): string {
  const code = (lastError ?? '').trim();
  return commonInvoiceDeliveryWait(code) ? '' : code;
}

export function isManualTbankReconciliationRetryError(error: string | null | undefined): boolean {
  const normalized = (error ?? '').trim().toLowerCase();
  return normalized.startsWith('manual_payment_tbank_reconciliation_in_progress:')
    || normalized.startsWith('manual_payment_tbank_reconciliation_retry:');
}

@Component({
  selector: 'app-common-billing',
  imports: [
    PaperInvoiceListComponent,
    AdminLayoutComponent,
    CommonManualPaymentAttributionModalComponent,
    CommonInvoiceOverdueTermComponent,
    CommonInvoiceReturnOrdersComponent,
    CommonInvoiceGuideComponent,
    PaymentRecipientPickerComponent,
    DatePipe,
    FormsModule,
    LoadErrorCardComponent,
    ManagerOrderCardComponent,
    ManagerOrderEditModalComponent,
    OrderPaymentRouteWindowsComponent,
    MobileBottomPagerComponent,
    RouterLink
  ],
  templateUrl: './common-billing.component.html',
  styleUrl: './common-billing.component.scss'
})
export class CommonBillingComponent implements OnDestroy {
  readonly deliveryStatusText = deliveryOperationMessage;
  readonly deliveryWait = commonInvoiceDeliveryWait;
  private readonly deliveryWatcher = new DeliveryStatusWatcher();

  readonly paymentInitNoPaymentButtonLabel = PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL;
  @ViewChild('invoiceOrderCardsViewport') private invoiceOrderCardsElement?: ElementRef<HTMLElement>;
  @ViewChild(PaperInvoiceListComponent) private paperInvoiceList?: PaperInvoiceListComponent;

  private readonly commonBillingApi = inject(CommonBillingApi);
  private readonly paymentsApi = inject(PaymentsApi);
  private readonly commonManualPaymentApi = inject(CommonManualPaymentAttributionApi);
  private readonly location = inject(Location);
  /** Set when the manager leaves the invoice the page was opened for (?invoiceId=…) by choosing another link. */
  private requestedInvoiceReleased = false;
  private readonly managerApi = inject(ManagerApi);
  private readonly auth = inject(AuthService);
  private readonly toastService = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly routeSubscription: Subscription;
  private accountsLoadSubscription?: Subscription;
  private invoiceLoadSubscription?: Subscription;
  private companySearchTimer: number | null = null;
  private companySearchRun = 0;
  private accountLoadRun = 0;
  private invoiceViewGeneration = 0;
  private invoiceReadRun = 0;
  private destroyed = false;

  readonly accounts = signal<CommonBillingAccountResponse[]>([]);
  /** Client-side filter of the «Все связи» list; it never changes the selected link. */
  readonly accountFilter = signal('');
  readonly visibleAccounts = computed(() => {
    const query = this.accountFilter().trim().toLocaleLowerCase('ru-RU');
    const accounts = this.accounts();
    if (!query) {
      return accounts;
    }
    return accounts.filter((account) => `${account.name ?? ''} #${account.id}`.toLocaleLowerCase('ru-RU').includes(query));
  });
  readonly selectedAccountId = signal<number | null>(null);
  readonly invoiceDetails = signal<CommonInvoiceDetailsResponse | null>(null);
  readonly loading = signal(false);
  readonly invoiceLoading = signal(false);
  readonly error = signal('');
  readonly mutating = signal('');
  readonly copied = signal('');
  readonly manualAttributionRequired = signal<boolean | null>(null);
  readonly manualAttributionMode = signal<CommonManualPaymentMode | null>(null);
  /** Open section of the side-panel manual; null while the manual is closed. */
  readonly guideSection = signal<CommonInvoiceGuideSection | null>(null);
  readonly companySearch = signal('');
  readonly companySearchResults = signal<CompanyCardItem[]>([]);
  readonly companySearchLoading = signal(false);
  readonly companySearchError = signal('');
  /** Shown in the route block right after a manager changes the recipient: «было → стало». */
  readonly lastRouteChange = signal<{ invoiceId: number; from: string; to: string; at: Date } | null>(null);
  /** The «Вернуть заказ в счёт» window: the invoice it was opened for, the server's list and the last refusal. */
  readonly returnOrdersWindow = signal<{ invoiceId: number } | null>(null);
  readonly returnableOrders = signal<CommonInvoiceReturnableOrdersResponse | null>(null);
  readonly returnableOrdersLoading = signal(false);
  readonly returnableOrdersError = signal('');
  readonly returnOrderRefusal = signal('');
  readonly draft = signal<AccountDraft>({
    name: '',
    enabled: true,
    autoRepeatOrders: true,
    managerId: '',
    invoiceCompanyId: ''
  });
  readonly managerInvoiceDetailMode = this.router.url.startsWith('/manager/common-billing');
  private readonly orderFacade = new ManagerBoardOrderFacade({
    openPaperInvoice: openPaperInvoiceDialog,
    managerApi: this.managerApi,
    toastService: this.toastService,
    loadBoard: () => this.loadSelectedInvoice(),
    errorMessage: (err, fallback) => apiErrorDetail(err, fallback),
    openCreatedCompanyOrders: () => undefined
  });
  readonly editOrder = this.orderFacade.editOrder;
  readonly orderDraft = this.orderFacade.orderDraft;
  readonly orderLoading = this.orderFacade.orderLoading;
  readonly orderSaving = this.orderFacade.orderSaving;
  readonly orderError = this.orderFacade.orderError;
  readonly orderDeleting = this.orderFacade.orderDeleting;
  readonly orderCancelingPayment = this.orderFacade.orderCancelingPayment;
  readonly paymentRouteContext = this.orderFacade.paymentRouteContext;
  /** The order editor's own windows: recipient choice for «Выдать номер карты» and the manual. */
  readonly orderPaymentFacade = this.orderFacade;
  readonly orderPaymentGuideOpen = signal(false);
  readonly paymentRouteContextLoading = this.orderFacade.paymentRouteContextLoading;
  readonly paymentRouteChanging = this.orderFacade.paymentRouteChanging;

  readonly selectedAccount = computed(() => {
    const id = this.selectedAccountId();
    return this.accounts().find((account) => account.id === id) ?? null;
  });
  readonly currentInvoice = computed(() => this.invoiceDetails()?.summary ?? this.selectedAccount()?.currentInvoice ?? null);
  readonly invoiceOrders = computed(() => this.invoiceDetails()?.orders ?? []);
  readonly paidInvoiceOrders = computed(() => this.invoiceOrders().filter(order => order.paid));
  readonly invoiceOrderCards = computed(() => {
    const unpaidIds = new Set(this.invoiceOrders().filter(order => !order.paid).map(order => order.orderId));
    return (this.invoiceDetails()?.orderCards ?? []).filter(order => unpaidIds.has(order.id));
  });
  readonly nextCycleOrders = computed(() => this.invoiceDetails()?.nextCycleOrders ?? []);
  readonly invoiceCardIndex = signal(0);
  readonly invoiceCardPageIndex = computed(() => Math.min(
    this.invoiceCardIndex(),
    Math.max(0, this.invoiceOrderCards().length - 1)
  ));
  readonly invoiceNeedsAttention = computed(() => this.currentInvoice()?.status === 'NEEDS_ATTENTION');
  readonly reviewApprovalCount = computed(() => this.invoiceOrders()
    .filter(order => order.orderStatus === 'В проверку' || order.orderStatus === 'На проверке')
    .length);
  readonly canApproveReviewOrders = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(invoice && invoice.status !== 'NEEDS_ATTENTION' && this.reviewApprovalCount() > 0);
  });
  readonly canReturnOrders = computed(() => canReturnOrdersToCommonInvoice(this.currentInvoice()));
  readonly attentionError = computed(() => (this.currentInvoice()?.lastError ?? '').trim().toLowerCase());
  readonly attentionHasLatePayment = computed(() => {
    const error = this.attentionError();
    return error.startsWith('late_tbank_payment') || error.startsWith('late_payment_');
  });
  readonly attentionHasFinalCancelFailure = computed(() => this.attentionError().startsWith('payment_cancel_failed_final'));
  readonly attentionHasStandaloneRouteConflict = computed(() => this.attentionError().startsWith('standalone_payment_route_conflict'));
  readonly attentionHasManualTbankReconciliationRetry = computed(() =>
    isManualTbankReconciliationRetryError(this.attentionError()));
  readonly attentionHasManualTbankPaymentDetected = computed(() =>
    this.attentionError().startsWith('manual_payment_tbank_payment_detected:'));
  readonly attentionHasPaymentInitCheck = computed(() => isPaymentInitManualCheckError(this.attentionError()));
  readonly attentionIsMigrationPaymentRegistry = computed(() => isMigrationPaymentRegistryError(this.attentionError()));
  readonly attentionPaymentEvidence = computed(() => {
    if (!this.attentionHasPaymentInitCheck() && !this.attentionIsMigrationPaymentRegistry()) {
      return [];
    }
    return commonInvoicePaymentEvidence(
      this.currentInvoice(),
      this.invoiceDetails()?.paymentRefs,
      this.attentionIsMigrationPaymentRegistry()
    );
  });
  readonly paymentInitCheckSnapshot = computed(() => commonInvoicePaymentEvidenceSnapshot(
    this.invoiceDetails(),
    this.expectedCurrentInvoiceId()
  ));
  readonly paymentInitCheckReady = computed(() => !this.invoiceLoading() && this.paymentInitCheckSnapshot() !== null);
  readonly attentionRequiresManualCheck = computed(() => {
    return this.attentionHasLatePayment()
      || this.attentionHasPaymentInitCheck()
      || this.attentionIsMigrationPaymentRegistry()
      || this.attentionHasFinalCancelFailure();
  });
  /** The client message is still in the queue: said as a wait in its own line, never as a problem of the invoice. */
  readonly invoiceDeliveryWait = computed(() =>
    commonInvoiceDeliveryWait(this.currentInvoice()?.lastError, this.invoiceDetails()?.delivery) ?? '');
  readonly invoiceProblemRaw = computed(() => commonInvoiceProblemCode(this.currentInvoice()?.lastError));
  readonly invoicePaymentNotificationError = computed(() => (this.currentInvoice()?.paymentSuccessNotificationError ?? '').trim());
  readonly canResolveTechnicalTail = computed(() => {
    const invoice = this.currentInvoice();
    const error = this.invoiceProblemRaw().toLowerCase();
    return Boolean(
      invoice
        && invoice.status === 'DISABLED'
        && invoice.paidOrders >= invoice.totalOrders
        && (error.startsWith('disabled:') || error.startsWith('empty:') || error.startsWith('merged_into:'))
    );
  });
  readonly canResolvePaymentNotification = computed(() => Boolean(this.currentInvoice() && this.invoicePaymentNotificationError()));
  readonly invoiceProblemTitle = computed(() => {
    if (this.invoicePaymentNotificationError()) {
      return 'Ошибка уведомления об оплате';
    }
    if (this.invoiceProblemRaw()) {
      return 'Проблема общего счета';
    }
    return 'Проверьте общий счет';
  });
  readonly invoiceProblem = computed(() => {
    const invoice = this.currentInvoice();
    if (!invoice) {
      return '';
    }
    const error = this.invoiceProblemRaw();
    if (error) {
      return this.humanCommonInvoiceError(error);
    }
    const notificationError = this.invoicePaymentNotificationError();
    if (notificationError) {
      return this.humanPaymentNotificationError(notificationError);
    }
    if (isIncompletePartiallyPaidInvoice(invoice)) {
      return '';
    }
    return commonInvoiceStatusProblem(invoice.status, invoice.lastError);
  });
  readonly invoiceProblemSteps = computed(() => this.commonInvoiceProblemSteps());
  readonly invoiceProblemActionLabel = computed(() => {
    const notificationError = this.invoicePaymentNotificationError().toLowerCase();
    if (notificationError) {
      return 'После ручной отправки или исправления нажмите "Уведомление обработано".';
    }
    if (this.attentionIsMigrationPaymentRegistry() && this.attentionHasPaymentInitCheck()) {
      return paymentInitNoPaymentActionLabel();
    }
    if (this.attentionIsMigrationPaymentRegistry()) {
      return 'Этот тип миграционного конфликта нельзя закрыть из карточки. Передайте реквизиты администратору для отдельного разбора.';
    }
    if (this.attentionHasPaymentInitCheck()) {
      return paymentInitNoPaymentActionLabel();
    }
    if (this.attentionHasManualTbankReconciliationRetry()) {
      return 'Нажмите «Повторить сверку оплаты»: система ещё раз проверит банк платежа и закроет счёт только после безопасной отмены ссылки.';
    }
    if (this.attentionHasManualTbankPaymentDetected()) {
      return 'По банковской ссылке есть движение денег. Не подтверждайте перевод повторно: сначала сверьте обе оплаты с администратором.';
    }
    if (this.invoiceNeedsAttention()) {
      return 'После проверки выберите подходящее действие в блоке «Решение проблемы» ниже.';
    }
    if (this.canResolveTechnicalTail()) {
      return 'После сверки отключенной связи можно закрыть технический хвост.';
    }
    return '';
  });
  readonly invoiceProblemChatUrl = computed(() => {
    return this.invoiceOrderCards()
      .map((order) => (order.companyUrlChat ?? '').trim())
      .find((url) => Boolean(url)) ?? '';
  });
  readonly paymentNotificationCopyText = computed(() => this.buildPaymentNotificationText());
  readonly paperModeSwitchNeedsRetry = computed(() => {
    const error = this.invoiceProblemRaw().toLowerCase();
    return error.startsWith('paper_invoice_mode_switch_retry:')
      || error.startsWith('paper_invoice_mode_switch_in_progress:')
      || error.startsWith('paper_invoice_mode_switch_state_changed:');
  });
  /** The side panel may switch the invoice to Tochka documents (or open them in paper mode). */
  readonly paperModeSwitchAllowed = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(invoice && this.canManagePaperInvoices()
      && (!this.invoiceNeedsAttention() || this.paperModeSwitchNeedsRetry())
      && (invoice.status !== 'PAID' || invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE'));
  });
  /** One entry point to the Tochka document dialog: previously offered both by the list and the panel. */
  readonly paperDocumentsActionVisible = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(invoice && this.canManagePaperInvoices()
      && (invoice.status !== 'ARCHIVED' || this.paperModeSwitchAllowed()));
  });
  /** How the payment of this invoice is confirmed, so the manager knows when a manual mark is needed. */
  readonly paymentHint = computed(() => {
    const invoice = this.currentInvoice();
    if (!invoice) {
      return '';
    }
    if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return this.paperWorkflow().bankManaged
        ? 'Оплату по счёту Точки подтверждает банк — счёт закроется сам.'
        : 'Когда деньги по счёту придут, отметьте оплату.';
    }
    if (isBankPaymentRouteType(invoice.paymentRouteType)) {
      return 'По ссылке оплату подтверждает банк — счёт закроется сам. Вручную отмечайте, только если клиент заплатил не по ссылке.';
    }
    if (invoice.contractorPaymentRoute) {
      return 'Клиент переводит на номер карты получателя из счёта. Отметьте оплату, когда деньги видны в выписке.';
    }
    return 'Отметьте оплату, когда деньги видны в выписке получателя.';
  });
  /** Companies the invoice can be sent to: the link's own companies instead of a typed ID. */
  readonly invoiceCompanyOptions = computed(() => {
    const account = this.selectedAccount();
    const options = account
      ? (account.companies ?? []).filter((company) => company.enabled)
        .map((company) => ({ id: company.companyId, title: company.companyTitle || `Компания ${company.companyId}` }))
      : [];
    const current = Number(this.draft().invoiceCompanyId);
    if (current && !options.some((option) => option.id === current)) {
      const title = account?.invoiceCompanyId === current && account.invoiceCompanyTitle
        ? account.invoiceCompanyTitle
        : `Компания ${current}`;
      options.push({ id: current, title: `${title} (не в связи)` });
    }
    return options;
  });
  /** A manager is the default; only owner and admin reassign the link to another manager. */
  readonly canChangeAccountManager = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly invoicePaidPercent = computed(() => {
    const invoice = this.currentInvoice();
    const amount = invoice?.amountKopecks ?? 0;
    if (!invoice || amount <= 0) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(((invoice.paidKopecks ?? 0) / amount) * 100)));
  });
  /** A plain-language line about where the invoice is now; problems have their own block. */
  readonly invoiceStageHint = computed(() => {
    const invoice = this.currentInvoice();
    // While the message is in the queue the wait line speaks instead: a stage hint could contradict it.
    if (!invoice || this.invoiceProblem() || this.invoiceDeliveryWait()) {
      return '';
    }
    const paper = invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE';
    switch (invoice.status) {
      case 'COLLECTING':
        return 'Счёт собирается: в него попадают готовые заказы компаний. Отправлять его пока рано.';
      case 'READY':
        if (invoice.sentAt) {
          return 'Клиент уведомлён. Ждём оплату.';
        }
        return paper
          ? 'Счёт готов. Сообщите клиенту о готовности работ — счёт и акт Точки придут отдельно.'
          : 'Счёт готов, но клиенту ещё не отправлен. Проверьте заказы и нажмите «Отправить счёт».';
      case 'INVOICED':
        return paper ? 'Клиент уведомлён. Ждём оплату по счёту и акту Точки.' : 'Счёт отправлен клиенту. Ждём оплату.';
      case 'REMINDER':
        return 'Клиенту отправлено напоминание. Ждём оплату.';
      case 'PARTIALLY_PAID':
        return 'Часть заказов оплачена. Ждём оплату остальных.';
      case 'PAID':
        return 'Счёт оплачен. Действий не требуется.';
      case 'UNPAID':
        return 'Срок оплаты прошёл: неоплаченные заказы переведены в «Не оплачено».';
      case 'BAN':
        return 'Неоплаченные заказы счёта переведены в Бан.';
      case 'DISABLED':
        return 'Счёт отключён.';
      case 'ARCHIVED':
        return 'Счёт в архиве: сохранён для истории, без требования оплаты.';
      default:
        return '';
    }
  });
  readonly attentionRetryEnabled = computed(() => this.invoiceNeedsAttention()
    && !this.attentionRequiresManualCheck()
    && !this.attentionHasStandaloneRouteConflict()
    && !this.attentionHasManualTbankReconciliationRetry()
    && !this.attentionHasManualTbankPaymentDetected()
    && !this.invoiceProblemRaw().toLowerCase().startsWith('paper_invoice_mode_switch_'));
  readonly attentionResolveEnabled = computed(() => {
    return this.invoiceNeedsAttention()
      && !this.attentionRequiresManualCheck()
      && !this.attentionHasStandaloneRouteConflict()
      && !this.attentionHasManualTbankReconciliationRetry()
      && !this.attentionHasManualTbankPaymentDetected()
      && !this.invoiceProblemRaw().toLowerCase().startsWith('paper_invoice_mode_switch_');
  });
  readonly readyForSending = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && invoice.totalOrders > 0
        && invoice.readyOrders >= invoice.totalOrders
        && ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status)
    );
  });
  readonly canManagePaperInvoices = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  });
  readonly paperInvoiceState = signal<PaperInvoiceListState | null>(null);
  readonly paperWorkflow = computed(() => paperInvoiceWorkflow(this.currentInvoice(), this.paperInvoiceState()));
  readonly canMarkPaperInvoiceIssued = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole([...PAPER_INVOICE_ISSUED_ROLES]) && this.paperWorkflow().manualAllowed;
  });
  readonly paperInvoiceAwaitingDeliveryConfirmation = computed(() =>
    paperInvoiceRequiresDeliveryConfirmation(this.currentInvoice())
  );
  readonly canRecordBadReviewReceipt = computed(() => {
    const invoice = this.currentInvoice();
    return invoice?.status === 'NEEDS_ATTENTION' && invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
      && this.manualAttributionRequired() === true && invoice.paidKopecks === 0
      && (invoice.lastError?.startsWith('bad_review_supplement_required:') || invoice.lastError?.startsWith('Ожидаем закрытия прежней банковской ссылки.'));
  });
  readonly canMarkPaid = computed(() => {
    const invoice = this.currentInvoice();
    return this.canRecordBadReviewReceipt() || canMarkCommonInvoicePaid(invoice) && (invoice?.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
      || this.paperWorkflow().contextAvailable && !this.paperWorkflow().bankManaged);
  });
  readonly canConfirmContractorSource = computed(() => {
    this.auth.tokenParsed();
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && invoice.contractorPaymentRoute
        && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])
        && ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'UNPAID'].includes(invoice.status)
    );
  });
  /**
   * «Не оплачен» is set by the autoresponder when the payment term ends. The manual button stays
   * with the owner and administrators for the days the autoresponder is paused or switched off.
   */
  readonly manualUnpaidAllowed = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly canMarkUnpaid = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && this.manualUnpaidAllowed()
        && invoice.totalOrders > 0
        && invoice.paidOrders < invoice.totalOrders
        && ['INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status)
    );
  });
  readonly canMarkBan = computed(() => this.currentInvoice()?.status === 'UNPAID');
  readonly canArchiveInvoice = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && invoice.status === 'COLLECTING'
        && invoice.totalOrders > 0
        && invoice.paidKopecks <= 0
        && invoice.paidOrders <= 0
    );
  });
  readonly canDeleteInvoiceWithOrders = computed(() => {
    this.auth.tokenParsed();
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])
        && !['PAID', 'PARTIALLY_PAID'].includes(invoice.status)
        && invoice.paidKopecks <= 0
        && invoice.paidOrders <= 0
    );
  });
  readonly metrics = computed(() => {
    const accounts = this.accounts();
    const invoices = accounts.map((account) => account.currentInvoice).filter(Boolean) as CommonInvoiceSummaryResponse[];
    return [
      { label: 'Плательщики', value: accounts.length, icon: 'account_tree' },
      { label: 'Включены', value: accounts.filter((account) => account.enabled).length, icon: 'toggle_on' },
      { label: 'Ожидают счет', value: invoices.filter((invoice) => invoice.status === 'READY' || invoice.status === 'COLLECTING').length, icon: 'pending_actions' },
      { label: 'К оплате', value: this.formatKopecks(invoices.reduce((sum, invoice) => sum + invoice.remainingKopecks, 0)), icon: 'payments' }
    ];
  });
  readonly pageTitle = computed(() => `Заказы - ${this.selectedAccountTitle()}`);

  constructor() {
    this.routeSubscription = this.route.queryParamMap.subscribe(() => {
      this.requestedInvoiceReleased = false;
      this.invalidateInvoiceView();
      this.load();
    });
  }

  ngOnDestroy(): void {
    this.deliveryWatcher.cancel();
    this.destroyed = true;
    this.accountLoadRun += 1;
    this.companySearchRun += 1;
    this.invalidateInvoiceView();
    this.routeSubscription.unsubscribe();
    this.accountsLoadSubscription?.unsubscribe();
    this.invoiceLoadSubscription?.unsubscribe();
    if (this.companySearchTimer != null) {
      window.clearTimeout(this.companySearchTimer);
    }
  }

  load(): void {
    const loadRun = ++this.accountLoadRun;
    this.accountsLoadSubscription?.unsubscribe();
    this.loading.set(true);
    this.error.set('');

    const requestedInvoiceId = this.requestedInvoiceId();
    if (requestedInvoiceId && this.currentInvoice()?.id !== requestedInvoiceId) {
      this.invoiceDetails.set(null);
      this.selectedAccountId.set(null);
    }
    if (this.managerInvoiceDetailMode) {
      this.accounts.set([]);
      this.selectedAccountId.set(null);
      if (!requestedInvoiceId) {
        this.invoiceDetails.set(null);
        this.error.set('Откройте общий счет из карточки заказа.');
        this.loading.set(false);
        return;
      }
      this.loadInvoiceByRequestedId(requestedInvoiceId);
      this.loading.set(false);
      return;
    }

    this.accountsLoadSubscription = this.commonBillingApi.accounts().subscribe({
      next: (accounts) => {
        if (!this.isCurrentAccountLoad(loadRun)) {
          return;
        }
        this.accounts.set(accounts ?? []);
        const requestedAccount = requestedInvoiceId
          ? this.accounts().find((account) => account.currentInvoice?.id === requestedInvoiceId)
          : null;
        const selectedStillExists = this.accounts().some((account) => account.id === this.selectedAccountId());
        if (requestedAccount) {
          this.selectedAccountId.set(requestedAccount.id);
        } else if (requestedInvoiceId) {
          this.loadInvoiceByRequestedId(requestedInvoiceId);
        } else if (!selectedStillExists) {
          this.selectedAccountId.set(this.accounts()[0]?.id ?? null);
        }
        this.applySelectedDraft();
        if (!requestedInvoiceId || requestedAccount) {
          this.loadSelectedInvoice();
        }
        this.loading.set(false);
      },
      error: (err) => {
        if (!this.isCurrentAccountLoad(loadRun)) {
          return;
        }
        if (err instanceof ClientContractError) {
          this.invalidateInvoiceView();
          this.accounts.set([]);
          this.selectedAccountId.set(null);
          this.invoiceDetails.set(null);
          this.applySelectedDraft();
        }
        const message = apiErrorDetail(err, 'Не удалось загрузить общие счета');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Общие счета не загрузились', message);
      }
    });
  }

  selectAccount(account: CommonBillingAccountResponse): void {
    if (this.selectedAccountId() === account.id) {
      return;
    }
    this.releaseRequestedInvoice();
    this.invalidateInvoiceView();
    this.selectedAccountId.set(account.id);
    this.invoiceDetails.set(null);
    this.applySelectedDraft();
    this.loadSelectedInvoice();
  }

  /**
   * The page can be opened for one invoice (?invoiceId=…). While that pin stays, the read of any
   * other invoice is discarded as stale, so an explicit choice of another link drops the pin.
   */
  private releaseRequestedInvoice(): void {
    if (this.managerInvoiceDetailMode || this.requestedInvoiceId() === null) {
      return;
    }
    this.requestedInvoiceReleased = true;
    const url = this.router.createUrlTree([], {
      relativeTo: this.route,
      queryParams: { invoiceId: null },
      queryParamsHandling: 'merge'
    }).toString();
    this.location.replaceState(url);
  }

  saveAccount(): void {
    const account = this.selectedAccount();
    const draft = this.draft();
    if (!account || !draft.name.trim() || this.mutating()) {
      return;
    }

    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = `save-${account.id}`;
    this.invalidateAccountLoad();
    this.mutating.set(mutationKey);
    this.commonBillingApi.updateAccount(account.id, {
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: this.optionalNumber(draft.managerId),
      invoiceCompanyId: this.optionalNumber(draft.invoiceCompanyId),
      companyIds: this.enabledCompanyIds(account)
    }).subscribe({
      next: (updated) => {
        if (!this.isCurrentPageView(viewGeneration)) {
          return;
        }
        this.replaceAccount(updated);
        this.applySelectedDraft();
        this.mutating.set('');
        this.toastService.success('Настройки сохранены');
      },
      error: (err) => {
        if (this.isCurrentPageView(viewGeneration) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось сохранить общего плательщика');
        }
      }
    });
  }

  onCompanySearchChange(value: string): void {
    this.companySearch.set(value);
    this.companySearchError.set('');
    const run = ++this.companySearchRun;
    if (this.companySearchTimer != null) {
      window.clearTimeout(this.companySearchTimer);
    }

    const query = value.trim();
    if (query.length < 2) {
      this.companySearchResults.set([]);
      this.companySearchLoading.set(false);
      return;
    }

    this.companySearchLoading.set(true);
    this.companySearchTimer = window.setTimeout(() => {
      this.companySearchTimer = null;
      this.managerApi.getBoard({
        section: 'companies',
        status: 'Все',
        keyword: query,
        pageNumber: 0,
        pageSize: 8,
        sortDirection: 'desc'
      }).subscribe({
        next: (board) => {
          if (run !== this.companySearchRun) {
            return;
          }
          this.companySearchResults.set(board.companies.content ?? []);
          this.companySearchLoading.set(false);
        },
        error: (err) => {
          if (run !== this.companySearchRun) {
            return;
          }
          this.companySearchResults.set([]);
          this.companySearchError.set(apiErrorDetail(err, 'Поиск компаний не сработал'));
          this.companySearchLoading.set(false);
        }
      });
    }, 260);
  }

  selectCompany(company: CompanyCardItem): void {
    if (this.companyAlreadySelected(company.id) || this.mutating()) {
      return;
    }
    // Companies are added only to an existing link; a new link is created in the company editor.
    if (this.selectedAccount()) {
      this.addCompany(company);
    }
  }

  addCompany(company: CompanyCardItem): void {
    const account = this.selectedAccount();
    if (!account || !company?.id || this.mutating() || this.companyAlreadySelected(company.id)) {
      return;
    }

    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = `add-company-${company.id}`;
    this.invalidateAccountLoad();
    this.mutating.set(mutationKey);
    this.commonBillingApi.addCompany(account.id, company.id).subscribe({
      next: (updated) => {
        if (!this.isCurrentPageView(viewGeneration)) {
          return;
        }
        this.replaceAccount(updated);
        this.clearCompanySearch();
        this.applySelectedDraft();
        this.mutating.set('');
        this.toastService.success('Компания добавлена в общий счет');
      },
      error: (err) => {
        if (this.isCurrentPageView(viewGeneration) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось добавить компанию');
        }
      }
    });
  }

  removeCompany(companyId: number): void {
    const account = this.selectedAccount();
    if (!account || this.mutating()) {
      return;
    }

    const confirmed = window.confirm('Исключить компанию из будущих общих счетов?');
    if (!confirmed) {
      return;
    }
    const detachCurrent = window.confirm(
      'Отключить также неоплаченные заказы этой компании из текущего общего счета? Отмена оставит текущую пачку как есть.'
    );

    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = `remove-company-${companyId}`;
    this.invalidateAccountLoad();
    this.mutating.set(mutationKey);
    this.commonBillingApi.removeCompany(account.id, companyId, detachCurrent).subscribe({
      next: (updated) => {
        if (!this.isCurrentPageView(viewGeneration)) {
          return;
        }
        this.replaceAccount(updated);
        this.applySelectedDraft();
        this.mutating.set('');
        this.toastService.success(detachCurrent ? 'Компания исключена, текущие неоплаченные позиции отключены' : 'Компания исключена из будущих счетов');
      },
      error: (err) => {
        if (this.isCurrentPageView(viewGeneration) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось исключить компанию');
        }
      }
    });
  }

  sendInvoice(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating()) {
      return;
    }
    this.invoiceAction(invoice.id, 'send-invoice', () => this.commonBillingApi.sendInvoice(invoice.id), 'Общий счет отправлен');
  }

  reconcilePaperInvoice(): void {
    const invoice=this.currentInvoice();
    if(!invoice || this.mutating() || invoice.status==='PAID' || !this.paperWorkflow().bankManaged) return;
    this.invoiceAction(invoice.id,'paper-invoice-reconcile',
      ()=>this.commonBillingApi.reconcilePaperInvoice(invoice.id).pipe(switchMap(()=>this.commonBillingApi.invoice(invoice.id))),
      'Сверка с банком запрошена; состояние счёта обновлено');
  }

  markInvoicePaid(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canMarkPaid()) {
      return;
    }
    if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      const evidence = this.requestManualPaymentEvidence(
        'Подтверждение оплаты бумажного счёта',
        'Подтвердить поступление денег по бумажному счёту владельца? Все заказы будут закрыты штатной логикой без начисления получателю-реквизитодержателю.'
      );
      if (!evidence) {
        return;
      }
      this.invoiceAction(
        invoice.id,
        'paper-invoice-paid',
        () => this.commonBillingApi.markPaperInvoicePaid(invoice.id, evidence),
        'Бумажный счёт закрыт оплатой'
      );
      return;
    }
    if (this.manualAttributionRequired() == null) {
      this.toastService.error('Режим оплаты загружается', 'Повторите действие через несколько секунд');
      this.loadManualAttributionMode(invoice.id);
      return;
    }
    if (this.manualAttributionRequired()) {
      this.manualAttributionMode.set(
        isBankPaymentRouteType(invoice.paymentRouteType) ? 'TBANK_ROUTE_FALLBACK' : 'STANDARD'
      );
      return;
    }
    const evidence = this.requestManualPaymentEvidence(
      'Подтверждение ручной оплаты общего счёта',
      'Отметить весь общий счет оплаченным? Все заказы внутри перейдут через штатную логику оплаты.'
    );
    if (!evidence) return;
    this.invoiceAction(
      invoice.id,
      'mark-paid',
      () => this.commonBillingApi.markPaid(invoice.id, evidence),
      'Общий счет закрыт оплатой'
    );
  }

  changePaperInvoiceMode(legacyPaper = false): void {
    const invoice = this.currentInvoice();
    if (invoice && !legacyPaper) {
      openPaperInvoiceDialog({ kind: 'COMMON_INVOICE', id: invoice.id }, () => this.changePaperInvoiceMode(true), () => this.loadSelectedInvoice());
      return;
    }
    if (!invoice || !this.canManagePaperInvoices() || this.mutating()) {
      return;
    }
    const paperEnabled = invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE';
    const nextMode = paperEnabled ? 'AUTO_ROUTING' : 'OWNER_PAPER_INVOICE';
    if (paperEnabled && !window.confirm('Вернуть автоматическое распределение? Если бумажный счёт уже отправлен, подтвердите его отзыв и отсутствие поступления по выписке.')) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'change-paper-invoice-mode',
      () => this.commonBillingApi.changeInvoicePaymentMode(invoice.id, nextMode, !!invoice.paperInvoiceIssuedAt),
      paperEnabled ? 'Автоматическое распределение восстановлено' : 'Режим бумажного счёта включён'
    );
  }
  openPaperDocuments(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !this.paperDocumentsActionVisible()) {
      return;
    }
    // Same dialog as before; the legacy mode switch stays limited to the states where the panel offered it.
    const switchAllowed = this.paperModeSwitchAllowed();
    openPaperInvoiceDialog(
      { kind: 'COMMON_INVOICE', id: invoice.id },
      switchAllowed ? () => this.changePaperInvoiceMode(true) : () => {},
      () => {
        this.paperInvoiceList?.load();
        this.refreshPaperInvoiceList();
      }
    );
  }
  refreshPaperInvoiceList(): void {
    const invoiceId = this.managerInvoiceDetailMode ? this.requestedInvoiceId() : null;
    if (invoiceId) this.loadInvoiceByRequestedId(invoiceId);
    else this.loadSelectedInvoice();
  }

  /** Re-reads the invoice shown on the page, whichever way the page was opened. */
  private reloadCurrentInvoice(): void {
    const invoiceId = this.managerInvoiceDetailMode ? this.requestedInvoiceId() : null;
    if (invoiceId) this.loadInvoiceByRequestedId(invoiceId);
    else this.loadSelectedInvoice();
  }

  /** The «Выдать номер карты» window: what it was opened with, the people and the last refusal. */
  readonly recipientPicker = signal<{
    invoiceId: number;
    target: CommonInvoicePaymentRouteChangeTarget;
    token: string;
    amountKopecks: number;
    currentRecipient: string;
    autoHint: string;
    before: string;
  } | null>(null);
  readonly recipientPickerOptions = signal<PaymentRecipientOption[] | null>(null);
  /** The owner's card of the window; null where the server does not offer it. */
  readonly recipientPickerOwnerCard = signal<PaymentRecipientOwnerCard | null>(null);
  /** What the automatic choice will do: the employees' chain, and the owner's card after it where it may be issued. */
  readonly recipientPickerAutoHint = computed(() => {
    const picker = this.recipientPicker();
    if (!picker) {
      return '';
    }
    const card = this.recipientPickerOwnerCard();
    if (!card || card.current || card.blockReason) {
      return picker.autoHint;
    }
    return picker.target === 'DIFFERENT_RECIPIENT'
      ? 'Система выдаст номер другого получателя — платёжного задания или менеджера, если у него хватает резерва. '
        + 'Нынешний получатель исключается. Если не подойдёт никто из сотрудников, клиент получит карту владельца: '
        + 'её лимит на этот месяц вмещает счёт.'
      : 'Система возьмёт платёжное задание, затем специалиста заказа, затем менеджера — первого, у кого хватает резерва. '
        + 'Если не подойдёт никто из сотрудников, клиент получит карту владельца: её лимит на этот месяц вмещает счёт.';
  });
  readonly recipientPickerLoading = signal(false);
  readonly recipientPickerError = signal('');
  readonly recipientPickerSubmitError = signal('');

  loadRecipientPickerOptions(): void {
    const picker = this.recipientPicker();
    if (!picker) {
      return;
    }
    this.recipientPickerLoading.set(true);
    this.recipientPickerError.set('');
    this.commonBillingApi.commonInvoiceRouteRecipients(picker.invoiceId).subscribe({
      next: (response) => {
        if (this.recipientPicker()?.invoiceId !== picker.invoiceId) {
          return;
        }
        this.recipientPickerOptions.set(response.recipients ?? []);
        this.recipientPickerOwnerCard.set(response.ownerCard ?? null);
        this.recipientPickerLoading.set(false);
      },
      error: (err) => {
        if (this.recipientPicker()?.invoiceId !== picker.invoiceId) {
          return;
        }
        this.recipientPickerError.set(apiErrorDetail(err, 'Не удалось загрузить сотрудников'));
        this.recipientPickerLoading.set(false);
      }
    });
  }

  closeRecipientPicker(): void {
    this.recipientPicker.set(null);
    this.recipientPickerOptions.set(null);
    this.recipientPickerOwnerCard.set(null);
    this.recipientPickerSubmitError.set('');
  }

  /** Issues the card number: the system's own choice, or the person the manager named. */
  submitRecipientChoice(choice: PaymentRecipientChoice): void {
    const picker = this.recipientPicker();
    if (!picker || this.mutating()) {
      return;
    }
    this.recipientPickerSubmitError.set('');
    this.invoiceAction(
      picker.invoiceId,
      'change-common-payment-route',
      () => this.commonBillingApi.changeCommonInvoicePaymentRoute(
        picker.invoiceId,
        picker.target,
        picker.token,
        null,
        choice.profileId
      ),
      '',
      (details) => {
        this.closeRecipientPicker();
        const after = this.routeDescription(details.summary);
        if (after === picker.before) {
          this.toastService.success('Запрос принят', 'Получатель сменится, когда банк подтвердит закрытие прежней ссылки.');
          return;
        }
        this.lastRouteChange.set({ invoiceId: picker.invoiceId, from: picker.before, to: after, at: new Date() });
        this.toastService.success('Получатель изменён', `${picker.before} → ${after}`);
      },
      (message, status) => {
        if (status === 423) {
          // The bank has to close the old link first; the request is saved and will finish by itself.
          this.closeRecipientPicker();
          return false;
        }
        // The window stays open with the refusal, so another person can be chosen at once.
        this.recipientPickerSubmitError.set(message);
        return true;
      }
    );
  }

  /**
   * A refused change of the payment method: the toast says first that nothing changed, then the
   * server's reason with its «Что делать». A bank that still has to answer (423) keeps its own notice.
   */
  private routeChangeRefused(message: string, status: number | undefined): boolean {
    if (status === 423) {
      return false;
    }
    this.toastService.error('Способ оплаты не изменён', message);
    return true;
  }

  changeCommonInvoicePaymentRoute(targetOverride?: CommonInvoicePaymentRouteChangeTarget): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return;
    }
    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = 'load-common-payment-route-change';
    this.mutating.set(mutationKey);
    this.commonBillingApi.commonInvoicePaymentRouteChangeContext(invoice.id).subscribe({
      next: (context) => {
        if (!this.isCurrentPageView(viewGeneration) || this.mutating() !== mutationKey) {
          return;
        }
        this.mutating.set('');
        if (!context.canChange && !((targetOverride === 'DIFFERENT_RECIPIENT' || targetOverride === 'EMPLOYEE_REQUISITES') && context.bankRecipientChangeAvailable)) {
          this.toastService.error('Способ оплаты нельзя изменить', context.blockReason || 'Нужна ручная сверка');
          return;
        }
        const target: CommonInvoicePaymentRouteChangeTarget = targetOverride
          ?? (context.currentTarget === 'OWNER_TBANK' && !context.ownerManualRoute
            ? 'EMPLOYEE_REQUISITES'
            : 'OWNER_TBANK');
        if (target === 'EMPLOYEE_REQUISITES' || target === 'DIFFERENT_RECIPIENT') {
          // A card number is issued from the window: the system's own choice or a person the manager names.
          this.recipientPickerSubmitError.set('');
          this.recipientPickerOptions.set(null);
          this.recipientPickerOwnerCard.set(null);
          this.recipientPicker.set({
            invoiceId: invoice.id,
            target,
            token: context.paymentEvidenceToken,
            amountKopecks: Math.max(0, (invoice.amountKopecks ?? 0) - (invoice.paidKopecks ?? 0)),
            currentRecipient: context.currentRecipient?.trim() ?? '',
            autoHint: target === 'DIFFERENT_RECIPIENT'
              ? 'Система выдаст номер другого получателя — платёжного задания или менеджера, если у него хватает резерва. '
                + 'Нынешний получатель исключается. Если не подойдёт никто, счёт не изменится.'
              : 'Система возьмёт платёжное задание, затем специалиста заказа, затем менеджера — первого, у кого хватает резерва. '
                + 'Если не подойдёт никто, счёт не изменится и ссылка останется действующей.',
            before: this.routeDescription(invoice)
          });
          this.loadRecipientPickerOptions();
          return;
        }
        const currentRecipient = context.currentRecipient?.trim()
          ? ` Получатель сейчас: ${context.currentRecipient}.`
          : '';
        if (!window.confirm(
          `Сменить способ оплаты на банковскую ссылку владельца?${currentRecipient} `
          + 'Старый безопасный резерв будет освобождён, новый маршрут создан заново, '
          + 'а клиенту сразу уйдёт обновлённое сообщение. Продолжайте только если клиент ещё не платил.'
        )) {
          return;
        }
        const before = this.routeDescription(invoice);
        this.invoiceAction(
          invoice.id,
          'change-common-payment-route',
          () => this.commonBillingApi.changeCommonInvoicePaymentRoute(
            invoice.id,
            target,
            context.paymentEvidenceToken
          ),
          '',
          (details) => {
            const after = this.routeDescription(details.summary);
            if (after === before) {
              this.toastService.success('Запрос принят', 'Получатель сменится, когда банк подтвердит закрытие прежней ссылки.');
              return;
            }
            this.lastRouteChange.set({ invoiceId: invoice.id, from: before, to: after, at: new Date() });
            this.toastService.success('Получатель изменён', `${before} → ${after}`);
          },
          (message, status) => this.routeChangeRefused(message, status)
        );
      },
      error: (err) => {
        if (this.isCurrentPageView(viewGeneration) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось проверить смену способа оплаты');
        }
      }
    });
  }

  reissueCommonInvoiceOwnerBankRoute(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !isBankPaymentRouteType(invoice.paymentRouteType)
      || this.mutating() || invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return;
    }
    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = 'load-common-owner-bank-reissue';
    this.mutating.set(mutationKey);
    this.commonBillingApi.commonInvoicePaymentRouteChangeContext(invoice.id).subscribe({
      next: (context) => {
        if (!this.isCurrentPageView(viewGeneration) || this.mutating() !== mutationKey) {
          return;
        }
        this.mutating.set('');
        if (!context.canReissueOwnerBank) {
          this.toastService.error(
            'Банковский профиль нельзя обновить',
            context.ownerBankReissueBlockReason || 'Нужна ручная сверка'
          );
          return;
        }
        const target = bankPaymentRouteLabel(
          'BANK_LINK',
          context.ownerBankTargetPaymentProfileName,
          context.ownerBankTargetProvider
        );
        if (!window.confirm(
          `Переиздать банковский способ оплаты на «${target}»? `
          + 'Операция разрешена только пока банк ещё не создавал платежную попытку. '
          + 'Клиенту сразу уйдёт сообщение с обновлённым способом оплаты.'
        )) {
          return;
        }
        this.invoiceAction(
          invoice.id,
          'reissue-common-owner-bank-route',
          () => this.commonBillingApi.changeCommonInvoicePaymentRoute(
            invoice.id,
            'OWNER_BANK_REISSUE',
            context.paymentEvidenceToken,
            context.ownerBankTargetPaymentProfileId
          ),
          `Общий счёт переведён на ${target}`,
          undefined,
          (message, status) => this.routeChangeRefused(message, status)
        );
      },
      error: (err) => {
        if (this.isCurrentPageView(viewGeneration) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось проверить переиздание банковского профиля');
        }
      }
    });
  }

  markPaperInvoiceIssued(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
      || invoice.paperInvoiceIssuedAt || !this.canMarkPaperInvoiceIssued() || this.mutating()) {
      return;
    }
    if (!window.confirm('Подтвердить, что счёт прикреплён и отправлен клиенту? После этой отметки начнутся автоматические напоминания.')) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'paper-invoice-issued',
      () => this.commonBillingApi.markPaperInvoiceIssued(invoice.id),
      'Отправка счёта клиенту подтверждена'
    );
  }

  confirmContractorPaymentSource(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canConfirmContractorSource()) {
      return;
    }
    if (!window.confirm(
      'Подтвердить поступление только после проверки выписки именно того получателя, чьи реквизиты указаны в этом счете?'
    )) {
      return;
    }
    const rawAmount = window.prompt(
      'Подтвержденная сумма именно по этому источнику накопительным итогом, ₽',
      ''
    );
    if (rawAmount === null) {
      return;
    }
    const confirmedTotalKopecks = Math.round(Number(rawAmount.replace(',', '.')) * 100);
    if (!Number.isSafeInteger(confirmedTotalKopecks) || confirmedTotalKopecks <= 0) {
      this.toastService.error('Укажите корректную положительную сумму');
      return;
    }
    const reason = window.prompt('Обязательное основание сверки выписки', 'Поступление найдено в выписке получателя')?.trim() ?? '';
    if (!reason) {
      this.toastService.error('Укажите основание сверки');
      return;
    }
    this.invoiceAction(
      invoice.id,
      'contractor-source-confirmation',
      () => this.commonBillingApi.confirmContractorSource(invoice.id, {
        recipientStatementChecked: true,
        paymentReceived: true,
        confirmedTotalKopecks,
        reason
      }),
      'Поступление учтено по конкретному счету'
    );
  }

  markInvoiceUnpaid(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canMarkUnpaid()) {
      return;
    }
    const confirmed = window.confirm('Перевести неоплаченные заказы общего счета в "Не оплачено"? Это запустит штатную работу с плохими задачами.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(invoice.id, 'mark-unpaid', () => this.commonBillingApi.markUnpaid(invoice.id), 'Неоплаченные заказы обработаны');
  }

  markInvoiceBan(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canMarkBan()) {
      return;
    }
    const confirmed = window.confirm('Перевести неоплаченные заказы общего счета в Бан?');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(invoice.id, 'mark-ban', () => this.commonBillingApi.markBan(invoice.id), 'Общий счет переведен в Бан');
  }

  archiveInvoice(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canArchiveInvoice()) {
      return;
    }
    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = `archive-preview-${invoice.id}`;
    this.mutating.set(mutationKey);
    this.commonBillingApi.archivePreview(invoice.id).subscribe({
      next: (preview) => {
        if (!this.isCurrentInvoiceView(viewGeneration, invoice.id)) {
          return;
        }
        if (!preview.allowed) {
          this.mutating.set('');
          this.toastService.error(
            'Общий счет нельзя архивировать',
            preview.blockers.join('; ') || 'Проверьте статусы заказов.'
          );
          return;
        }
        if (!window.confirm(`Архивировать общий счет #${invoice.id} и ${preview.totalOrders} заказов внутри?`)) {
          this.mutating.set('');
          return;
        }
        this.invoiceAction(
          invoice.id,
          'archive-invoice',
          () => this.commonBillingApi.archiveInvoice(invoice.id),
          'Общий счет и все заказы архивированы'
        );
      },
      error: (err) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoice.id) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось проверить возможность архивирования');
        }
      }
    });
  }

  deleteInvoiceWithOrders(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canDeleteInvoiceWithOrders()) {
      return;
    }
    const confirmed = window.confirm(
      `Удалить общий счет #${invoice.id} и все связанные с ним заказы (${invoice.totalOrders})? Действие нельзя отменить.`
    );
    if (!confirmed) {
      return;
    }

    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = `delete-invoice-${invoice.id}`;
    this.invalidateAccountLoad();
    this.mutating.set(mutationKey);
    this.commonBillingApi.deleteInvoiceWithOrders(invoice.id).subscribe({
      next: () => {
        if (!this.isCurrentInvoiceView(viewGeneration, invoice.id)) {
          return;
        }
        this.invoiceDetails.set(null);
        this.accounts.update((accounts) => accounts.map((account) =>
          account.currentInvoice?.id === invoice.id ? { ...account, currentInvoice: null } : account
        ));
        this.mutating.set('');
        this.toastService.success('Общий счет удален', 'Связанные заказы удалены штатной логикой');
        if (this.managerInvoiceDetailMode) {
          void this.router.navigate(['/orders']);
          return;
        }
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { invoiceId: null },
          queryParamsHandling: 'merge'
        }).then(() => this.load());
      },
      error: (err) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoice.id) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Общий счет не удален');
        }
      }
    });
  }

  retryAttention(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionRetryEnabled() || this.mutating()) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'retry-attention',
      () => this.commonBillingApi.retryAttention(invoice.id),
      'Повторная обработка запущена'
    );
  }

  repairPaymentRoute(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasStandaloneRouteConflict() || this.mutating()) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'repair-payment-route',
      () => this.commonBillingApi.repairPaymentRoute(invoice.id),
      'Единый платежный маршрут восстановлен'
    );
  }

  reportManualCardPayment(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasStandaloneRouteConflict() || this.mutating()) {
      return;
    }
    if (this.manualAttributionRequired() == null) {
      this.toastService.error('Режим оплаты загружается', 'Повторите действие через несколько секунд');
      this.loadManualAttributionMode(invoice.id);
      return;
    }
    if (this.manualAttributionRequired()) {
      this.manualAttributionMode.set('TBANK_FALLBACK');
      return;
    }
    const reasonValue = window.prompt(
      `Клиент оплатил общий счет №${invoice.id} переводом на карту. Укажите краткую причину:`
    );
    if (reasonValue === null) return;
    const reason = reasonValue.trim();
    if (!reason) {
      this.toastService.error('Оплата не подтверждена', 'Укажите причину ручной оплаты.');
      return;
    }
    this.invoiceAction(
      invoice.id,
      'manual-card-paid',
      () => this.commonBillingApi.reportManualCardPayment(invoice.id, reason),
      'Общий счет закрыт ручной оплатой'
    );
  }

  openGuide(section: CommonInvoiceGuideSection = 'overview', event?: Event): void {
    // A help button inside a <summary> must not also fold or unfold its group.
    event?.preventDefault();
    event?.stopPropagation();
    this.guideSection.set(section);
  }

  closeGuide(): void {
    this.guideSection.set(null);
  }

  closeManualAttribution(): void {
    this.manualAttributionMode.set(null);
  }

  completeManualAttribution(details: CommonInvoiceDetailsResponse): void {
    const invoiceId = details.summary.id;
    this.manualAttributionMode.set(null);
    this.invoiceDetails.set(details);
    this.accounts.update((accounts) => accounts.map((account) =>
      account.currentInvoice?.id === invoiceId
        ? { ...account, currentInvoice: details.summary }
        : account));
    this.toastService.success('Фактическое поступление учтено, общий счёт закрыт');
  }

  resolveAttention(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionResolveEnabled() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Закрыть ручную проверку общего счета? Используйте это только после сверки оплаты и заказов.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'resolve-attention',
      () => this.commonBillingApi.resolveAttention(invoice.id),
      'Ручная проверка закрыта'
    );
  }

  resolveTechnicalTail(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !this.canResolveTechnicalTail() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Закрыть технический хвост общего счета? Используйте это только если связь/перенос уже проверены.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'resolve-technical-tail',
      () => this.commonBillingApi.resolveTechnicalTail(invoice.id),
      'Технический хвост закрыт'
    );
  }

  resolvePaymentSuccessNotification(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !this.canResolvePaymentNotification() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Закрыть ошибку уведомления об оплате? Используйте это только после ручной отправки уведомления клиенту или исправления причины.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'resolve-payment-notification',
      () => this.commonBillingApi.resolvePaymentSuccessNotification(invoice.id),
      'Ошибка уведомления закрыта'
    );
  }

  applyLatePayment(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasLatePayment() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Распределить поздний банковский платеж по неоплаченным заказам общего счета?');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'apply-late-payment',
      () => this.commonBillingApi.applyLatePayment(invoice.id),
      'Поздний платеж распределен'
    );
  }

  confirmFinalPaymentCancelCheck(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasFinalCancelFailure() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Подтвердить, что старая банковская ссылка проверена вручную и больше не требует действий?');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'confirm-final-cancel-check',
      () => this.commonBillingApi.confirmFinalPaymentCancelCheck(invoice.id),
      'Проверка отмены закрыта'
    );
  }

  confirmPaymentInitCheck(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasPaymentInitCheck() || this.mutating()) {
      return;
    }
    const snapshot = this.paymentInitCheckSnapshot();
    if (this.invoiceLoading() || !snapshot || snapshot.invoiceId !== invoice.id) {
      this.toastService.error(
        'Сверка не начата',
        'Полные платежные реквизиты не загружены. Обновите карточку счета и повторите проверку.'
      );
      return;
    }
    const identifiers = snapshot.evidence
      .map(evidence => {
        const amount = evidence.amountKopecks == null
          ? 'не сохранена'
          : this.formatKopecks(evidence.amountKopecks);
        const status = evidence.status || 'не сохранён';
        const reason = evidence.reason || 'не сохранена';
        return `${evidence.label}: OrderId ${evidence.orderId}, PaymentId ${evidence.paymentId}, `
          + `сумма ${amount}, статус ${status}, причина ${reason}, `
          + `терминал ${evidence.terminalLabel} (${evidence.terminalKey})`;
      })
      .join('\n');
    const evidenceText = identifiers
      ? `\n\nПроверьте все записи:\n${identifiers}`
      : '\n\nИдентификаторы платежа не сохранены: проверьте операцию по счету вручную в банке платежа.';
    const confirmed = window.confirm(paymentInitNoPaymentConfirmation(evidenceText));
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'confirm-payment-init-check',
      () => this.commonBillingApi.confirmPaymentInitCheck(invoice.id, snapshot.evidenceToken),
      'В банке оплаты нет: техническая проверка закрыта'
    );
  }

  markOrderPaid(order: CommonInvoiceOrderResponse): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status === 'NEEDS_ATTENTION' || order.paid || this.mutating()) {
      return;
    }
    const evidence = this.requestManualPaymentEvidence(
      `Подтверждение оплаты заказа №${order.orderId}`,
      `Отметить заказ №${order.orderId} оплаченным внутри общего счёта?`
    );
    if (!evidence) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      `mark-order-${order.orderId}`,
      () => this.commonBillingApi.markOrderPaid(invoice.id, order.orderId, evidence),
      `Заказ ${order.orderId} отмечен внутри счета`
    );
  }

  paymentMethodLabel(order: CommonInvoiceOrderResponse): string {
    switch (order.paymentMethod) {
      case 'TBANK':
        return 'T‑Bank';
      case 'TOCHKA':
        return 'Точка Банк';
      case 'BANK':
        return 'Банк';
      case 'MANUAL':
      case 'MIXED':
        return 'Подтверждено вручную';
      case 'MANUAL_LEGACY':
        return 'Подтверждено вручную · старые данные';
      case 'OWNER_PAPER_INVOICE':
        return 'Бумажный счёт владельца';
      default:
        return 'Способ не указан';
    }
  }

  /** Who receives the money now, in plain words: the owner or the transfer recipient. */
  routeRecipientName(invoice: CommonInvoiceSummaryResponse): string {
    if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE' || isBankPaymentRouteType(invoice.paymentRouteType)) {
      return 'Владелец';
    }
    const recipient = (invoice.paymentRouteRecipient ?? '').trim();
    if (recipient) {
      return recipient.split(' · ')[0];
    }
    return invoice.paymentRouteManualTaskId ? `Платёжное задание №${invoice.paymentRouteManualTaskId}` : 'Не выбран';
  }

  /** How the client pays that recipient: owner's bank link, card number, Tochka documents. */
  routeMethodLabel(invoice: CommonInvoiceSummaryResponse): string {
    if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return 'счёт и акт Точки';
    }
    if (isBankPaymentRouteType(invoice.paymentRouteType)) {
      return `банковская ссылка · ${bankPaymentRouteLabel(invoice.paymentRouteType, invoice.paymentRouteProfileName, invoice.paymentRouteProvider)}`;
    }
    const details = (invoice.paymentRouteRecipient ?? '').trim().split(' · ').slice(1).join(' · ');
    switch (invoice.paymentRouteType) {
      case 'MANUAL_EXTERNAL_LINK':
        return details ? `внешняя ссылка · ${details}` : 'внешняя ссылка';
      case 'MANUAL_MOBILE_BANK':
        return details ? `номер карты · ${details}` : 'номер карты';
      case 'MANAGER_TEXT':
        return 'текст менеджера';
      default:
        return 'способ не выбран';
    }
  }

  routeDescription(invoice: CommonInvoiceSummaryResponse): string {
    return `${this.routeRecipientName(invoice)} — ${this.routeMethodLabel(invoice)}`;
  }

  paymentRouteLabel(invoice: CommonInvoiceSummaryResponse): string {
    if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return 'Бумажный счёт владельца';
    }
    if (isBankPaymentRouteType(invoice.paymentRouteType)) {
      return bankPaymentRouteLabel(
        invoice.paymentRouteType,
        invoice.paymentRouteProfileName,
        invoice.paymentRouteProvider
      );
    }
    switch (invoice.paymentRouteType) {
      case 'MANUAL_EXTERNAL_LINK':
        return invoice.paymentRouteRecipient?.trim()
          ? `Внешняя ссылка · ${invoice.paymentRouteRecipient.trim()}`
          : invoice.paymentRouteManualTaskId
          ? `Внешняя ссылка · задание #${invoice.paymentRouteManualTaskId}`
          : 'Внешняя ссылка';
      case 'MANUAL_MOBILE_BANK':
        return invoice.paymentRouteRecipient?.trim()
          ? `Реквизиты · ${invoice.paymentRouteRecipient.trim()}`
          : invoice.paymentRouteManualTaskId
          ? `Мобильный банк · задание #${invoice.paymentRouteManualTaskId}`
          : 'Мобильный банк';
      case 'MANAGER_TEXT':
        return 'Текст менеджера';
      default:
        return 'Не выбран';
    }
  }

  private requestManualPaymentEvidence(
    title: string,
    confirmation: string
  ): ManualPaymentConfirmationRequest | null {
    if (!window.confirm(confirmation)) {
      return null;
    }
    const commentValue = window.prompt(
      `${title}\n\nВведите комментарий (например: «сверено по выписке»).`
    );
    if (commentValue === null) {
      return null;
    }
    const evidence = {
      comment: commentValue.trim(),
      receiptUrl: ''
    };
    if (!evidence.comment) {
      this.toastService.error(
        'Оплата не подтверждена',
        'Для ручной оплаты обязательно укажите комментарий по проверке.'
      );
      return null;
    }
    return evidence;
  }

  approveReviewOrders(): void {
    const invoice = this.currentInvoice();
    const count = this.reviewApprovalCount();
    if (!invoice || !this.canApproveReviewOrders() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm(`Одобрить ${count} заказ(ов) в статусе "В проверку" или "На проверке"? Они перейдут в публикацию.`);
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'approve-review-orders',
      () => this.commonBillingApi.approveReviewOrders(invoice.id),
      `Одобрено заказов: ${count}`
    );
  }

  detachOrder(order: CommonInvoiceOrderResponse): void {
    const invoice = this.currentInvoice();
    if (!invoice || !order.detachable || this.mutating()) {
      return;
    }

    const target = order.paid
      ? 'Заказ отмечен оплаченным внутри общего счета, поэтому будет закрыт отдельно как оплаченный.'
      : `Заказ вернется в статус "${order.originalOrderStatus || 'Опубликовано'}".`;
    const confirmed = window.confirm(
      `Исключить заказ №${order.orderId} из общего счёта №${invoice.id}?\n\n` +
      `${target}\nСумма счёта будет пересчитана, неиспользованный резерв освобождён.\n\n` +
      'Подтвердите исключение заказа. Для отмены нажмите «Отмена».'
    );
    if (!confirmed) {
      return;
    }

    this.invoiceAction(
      invoice.id,
      `detach-order-${order.orderId}`,
      () => this.commonBillingApi.detachOrder(invoice.id, order.orderId),
      `Заказ ${order.orderId} отключен от общего счета`
    );
  }

  archiveDisputedOrder(order: CommonInvoiceOrderResponse): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.sentAt || !order.detachable || this.mutating()) {
      return;
    }
    if (!window.confirm(
      `Архивировать заказ №${order.orderId} и исключить его из общего счёта №${invoice.id}?\n\n` +
      'Заказ перейдёт в архив. Сумма счёта будет пересчитана, неиспользованный резерв освобождён.\n\n' +
      'Подтвердите архивирование. Для отмены нажмите «Отмена».'
    )) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      `archive-disputed-${order.orderId}`,
      () => this.commonBillingApi.archiveDisputedOrder(invoice.id, order.orderId),
      `Заказ ${order.orderId} убран из общего счета и архивирован`
    );
  }

  /** Opens the «Вернуть заказ в счёт» window; the list comes from the server for this invoice's own link. */
  openReturnOrders(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !canReturnOrdersToCommonInvoice(invoice) || this.mutating()) {
      return;
    }
    this.returnOrdersWindow.set({ invoiceId: invoice.id });
    this.returnableOrders.set(null);
    this.returnOrderRefusal.set('');
    this.loadReturnableOrders();
  }

  loadReturnableOrders(): void {
    const opened = this.returnOrdersWindow();
    if (!opened) {
      return;
    }
    this.returnableOrdersLoading.set(true);
    this.returnableOrdersError.set('');
    this.commonBillingApi.returnableOrders(opened.invoiceId).subscribe({
      next: (response) => {
        if (this.returnOrdersWindow()?.invoiceId !== opened.invoiceId) {
          return;
        }
        this.returnableOrders.set(response);
        this.returnableOrdersLoading.set(false);
      },
      error: (err) => {
        if (this.returnOrdersWindow()?.invoiceId !== opened.invoiceId) {
          return;
        }
        this.returnableOrdersError.set(apiErrorDetail(err, 'Не удалось загрузить заказы для возврата'));
        this.returnableOrdersLoading.set(false);
      }
    });
  }

  closeReturnOrders(): void {
    this.returnOrdersWindow.set(null);
    this.returnableOrders.set(null);
    this.returnableOrdersLoading.set(false);
    this.returnableOrdersError.set('');
    this.returnOrderRefusal.set('');
  }

  /**
   * Returns the order the manager confirmed in the window. Success closes the window and shows the
   * invoice with the order; a refusal stays in the window and in a toast that says nothing changed.
   */
  returnOrder(order: CommonInvoiceReturnableOrder): void {
    const opened = this.returnOrdersWindow();
    if (!opened || this.mutating()) {
      return;
    }
    this.returnOrderRefusal.set('');
    this.invoiceAction(
      opened.invoiceId,
      `return-order-${order.orderId}`,
      () => this.commonBillingApi.returnOrder(opened.invoiceId, order.orderId),
      `Заказ №${order.orderId} возвращён в общий счёт`,
      () => this.closeReturnOrders(),
      (message) => {
        this.toastService.error('Заказ не возвращён', message);
        this.returnOrderRefusal.set(message);
        // A refusal often means the page is stale: show the list and the invoice as they are now.
        this.loadReturnableOrders();
        this.reloadCurrentInvoice();
        return true;
      }
    );
  }

  async copyPublicUrl(): Promise<void> {
    const url = this.currentInvoice()?.publicUrl?.trim();
    if (!url) {
      return;
    }
    if (await copyTextToClipboard(url)) {
      this.copied.set('public-url');
      window.setTimeout(() => {
        if (this.copied() === 'public-url') {
          this.copied.set('');
        }
      }, 1600);
      this.toastService.success('Ссылка скопирована');
    } else {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  async copyPaymentNotificationText(): Promise<void> {
    await this.copyText(
      this.paymentNotificationCopyText(),
      'payment-notification-text',
      'Текст уведомления скопирован'
    );
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
      case 'NEEDS_ATTENTION':
        return 'Требует внимания';
      case 'PAID':
        return 'Оплачен';
      case 'UNPAID':
        return 'Не оплачен';
      case 'BAN':
        return 'Бан';
      case 'DISABLED':
        return 'Отключен';
      case 'ARCHIVED':
        return 'Архив';
      default:
        return 'Нет счета';
    }
  }

  statusClass(status?: string | null): string {
    if (status === 'PAID') {
      return 'status-pill paid';
    }
    if (status === 'READY' || status === 'INVOICED' || status === 'REMINDER') {
      return 'status-pill active';
    }
    if (status === 'PARTIALLY_PAID') {
      return 'status-pill partial';
    }
    if (status === 'UNPAID' || status === 'BAN' || status === 'DISABLED' || status === 'NEEDS_ATTENTION') {
      return 'status-pill danger';
    }
    return 'status-pill neutral';
  }

  formatKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format((value ?? 0) / 100)} ₽`;
  }

  orderAmountKopecks(order: OrderCardItem): number {
    return Math.round(managerPayableOrderSum(order) * 100);
  }

  selectedAccountTitle(): string {
    const account = this.selectedAccount();
    const invoice = this.currentInvoice();
    const title = (invoice?.title || invoice?.accountName || account?.invoiceCompanyTitle || account?.name || 'Общий счет').trim();
    return title.replace(/\s+-\s+общий счет.*$/i, '').trim() || title;
  }

  selectedAccountName(): string {
    return this.currentInvoice()?.accountName || this.selectedAccount()?.name || 'Общий счет';
  }

  accountsCountLabel(): string {
    const count = this.accounts().length;
    return `${count} ${this.pluralRu(count, 'связь', 'связи', 'связей')}`;
  }

  accountCompaniesLabel(account: CommonBillingAccountResponse): string {
    const count = account.companies?.length ?? 0;
    return `${count} ${this.pluralRu(count, 'компания', 'компании', 'компаний')}`;
  }

  trackAccount(_index: number, account: CommonBillingAccountResponse): number {
    return account.id;
  }

  trackCompany(_index: number, company: { companyId: number }): number {
    return company.companyId;
  }

  trackCompanySearch(_index: number, company: CompanyCardItem): number {
    return company.id;
  }

  companyAlreadySelected(companyId: number): boolean {
    const account = this.selectedAccount();
    return (account?.companies ?? []).some((company) => company.companyId === companyId && company.enabled);
  }

  trackOrder(_index: number, order: CommonInvoiceOrderResponse): number {
    return order.orderId;
  }

  trackOrderCard(_index: number, order: OrderCardItem): number {
    return order.id;
  }

  previousInvoiceCard(): void {
    this.showInvoiceCard(this.invoiceCardPageIndex() - 1);
  }

  nextInvoiceCard(): void {
    this.showInvoiceCard(this.invoiceCardPageIndex() + 1);
  }

  syncInvoiceCardIndex(event: Event): void {
    const container = event.currentTarget as HTMLElement | null;
    if (!container) {
      return;
    }
    const cards = Array.from(container.querySelectorAll<HTMLElement>('.invoice-order-card-wrap'));
    if (!cards.length) {
      this.invoiceCardIndex.set(0);
      return;
    }

    const containerBounds = container.getBoundingClientRect();
    const center = containerBounds.left + containerBounds.width / 2;
    let closestIndex = 0;
    let closestDistance = Number.POSITIVE_INFINITY;
    cards.forEach((card, index) => {
      const bounds = card.getBoundingClientRect();
      const distance = Math.abs(bounds.left + bounds.width / 2 - center);
      if (distance < closestDistance) {
        closestDistance = distance;
        closestIndex = index;
      }
    });
    if (this.invoiceCardIndex() !== closestIndex) {
      this.invoiceCardIndex.set(closestIndex);
    }
  }

  private showInvoiceCard(index: number): void {
    const cards = Array.from(
      this.invoiceOrderCardsElement?.nativeElement.querySelectorAll<HTMLElement>('.invoice-order-card-wrap') ?? []
    );
    if (!cards.length) {
      this.invoiceCardIndex.set(0);
      return;
    }
    const targetIndex = Math.max(0, Math.min(index, cards.length - 1));
    this.invoiceCardIndex.set(targetIndex);
    cards[targetIndex].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
  }

  invoiceOrderInfo(order: OrderCardItem): CommonInvoiceOrderResponse | null {
    return this.invoiceOrders().find((item) => item.orderId === order.id) ?? null;
  }

  orderCardActions(order: OrderCardItem): StatusAction[] {
    const disabledStatuses = new Set(['Выставлен счет', 'Напоминание', 'Не оплачено', 'Оплачено']);
    return managerOrderActions(order, false)
      .filter((action) => !disabledStatuses.has(action.status));
  }

  async copyPhone(phone?: string): Promise<void> {
    await this.copyText((phone ?? '').replace(/\D/g, ''), 'телефон', 'Телефон скопирован');
  }

  async copyOrderText(order: OrderCardItem, kind: 'review' | 'payment'): Promise<void> {
    if (kind === 'payment') {
      if (this.invoiceOrderInfo(order)?.ordinaryAfterPaperCancel) {
        try {
          const response = await firstValueFrom(this.paymentsApi.createOrderPaymentLink(order.id));
          await this.copyText(response.copyText || response.url, `payment-${order.id}`, 'Реквизиты заказа скопированы');
        } catch (error) {
          this.toastService.error('Счёт не создан', apiErrorDetail(error, 'Не удалось подготовить реквизиты заказа'));
        }
        return;
      }
      const url = this.currentInvoice()?.publicUrl?.trim();
      await this.copyText(
        url,
        `payment-${order.id}`,
        'Ссылка общего счета скопирована'
      );
      return;
    }
    await this.copyText(
      managerOrderReviewCopyText(order, []),
      `review-${order.id}`,
      'Текст проверки скопирован'
    );
  }

  openOrderDetails(order: OrderCardItem): void {
    void this.router.navigate(['/orders', order.companyId, order.id]);
  }

  openOrderEdit(order: OrderCardItem): void {
    this.orderFacade.openOrderEdit(order);
  }

  closeOrderEdit(): void {
    this.orderFacade.closeOrderEdit();
  }

  handleOrderEditDraftChange(change: ManagerOrderEditDraftChange): void {
    this.orderFacade.handleOrderEditDraftChange(change);
  }

  saveOrderEdit(): void {
    this.orderFacade.saveOrderEdit();
  }

  deleteOrderEdit(): void {
    this.orderFacade.deleteOrderEdit();
  }

  cancelOrderPayment(): void {
    this.orderFacade.cancelOrderPayment();
  }

  openPaymentRouteChange(): void {
    this.orderFacade.openPaymentRouteChange();
  }
  openOrderPaperInvoice(): void {this.orderFacade.openPaperInvoice();}

  isOwnerBankRoute(invoice: CommonInvoiceSummaryResponse | null | undefined): boolean {
    return Boolean(invoice && isBankPaymentRouteType(invoice.paymentRouteType));
  }

  /**
   * One «Выдать номер карты» action replaces two buttons. From a link any transfer number fits,
   * the same recipient included; from a number the client wants another person.
   */
  cardNumberRouteTarget(invoice: CommonInvoiceSummaryResponse | null | undefined): CommonInvoicePaymentRouteChangeTarget {
    return invoice && (isBankPaymentRouteType(invoice.paymentRouteType) || invoice.paymentRouteType === 'MANUAL_EXTERNAL_LINK')
      ? 'EMPLOYEE_REQUISITES'
      : 'DIFFERENT_RECIPIENT';
  }

  isOwnerManualRoute(invoice: CommonInvoiceSummaryResponse | null | undefined): boolean {
    return Boolean(invoice
      && (invoice.paymentRouteType === 'MANUAL_MOBILE_BANK'
        || invoice.paymentRouteType === 'MANUAL_EXTERNAL_LINK')
      && !invoice.contractorPaymentRoute
      && !invoice.paymentRouteManualTaskId);
  }

  retryManualTbankReconciliation(): void {
    const invoice = this.currentInvoice();
    if (!invoice
      || invoice.status !== 'NEEDS_ATTENTION'
      || !this.attentionHasManualTbankReconciliationRetry()
      || this.mutating()) {
      return;
    }
    this.manualAttributionMode.set('TBANK_ROUTE_FALLBACK');
  }

  closePaymentRouteChange(): void {
    this.orderFacade.closePaymentRouteChange();
  }

  changePaymentRoute(target: PaymentRouteChangeTarget): void {
    this.orderFacade.changePaymentRoute(target);
  }

  markOrderPaperInvoiceIssued(): void {
    this.orderFacade.markPaperInvoiceIssued();
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    if (action.status === 'Архив') {
      const position = this.invoiceDetails()?.orders.find(item => item.orderId === order.id);
      if (position) this.archiveDisputedOrder(position);
      return;
    }
    if (['Выставлен счет', 'Напоминание', 'Не оплачено', 'Оплачено'].includes(action.status)) {
      this.toastService.error('Одиночное финансовое действие отключено', 'Этот заказ входит в общий счет');
      return;
    }
    const invoiceId = this.currentInvoice()?.id;
    if (!invoiceId || this.mutating()) {
      return;
    }
    const viewGeneration = this.invoiceViewGeneration;
    const key = `order-${order.id}-${action.status}`;
    this.invalidateAccountLoad();
    this.mutating.set(key);
    this.managerApi.updateOrderStatus(order.id, action.status).subscribe({
      next: () => {
        if (!this.isCurrentInvoiceView(viewGeneration, invoiceId) || this.mutating() !== key) {
          return;
        }
        this.mutating.set('');
        this.toastService.success('Статус заказа изменен', `${order.companyTitle}: ${action.status}`);
        this.loadSelectedInvoice();
      },
      error: (err) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoiceId) && this.mutating() === key) {
          this.failMutation(err, managerErrorMessage(err, 'Не удалось изменить статус заказа'));
        }
      }
    });
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    const invoiceId = this.currentInvoice()?.id;
    const viewGeneration = this.invoiceViewGeneration;
    if (!invoiceId) {
      return;
    }
    this.managerApi.updateOrderCompanyNote(order.id, value).subscribe({
      next: () => {
        if (!this.isCurrentInvoiceView(viewGeneration, invoiceId)) {
          return;
        }
        this.toastService.success('Заметка компании сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.loadSelectedInvoice();
      },
      error: (err) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoiceId)) {
          this.toastService.error('Заметка не сохранена', apiErrorDetail(err, 'Не удалось сохранить заметку компании'));
        }
      }
    });
  }

  saveOrderCardNote(order: OrderCardItem, value: string): void {
    const invoiceId = this.currentInvoice()?.id;
    const viewGeneration = this.invoiceViewGeneration;
    if (!invoiceId) {
      return;
    }
    this.managerApi.updateOrderNote(order.id, value).subscribe({
      next: () => {
        if (!this.isCurrentInvoiceView(viewGeneration, invoiceId)) {
          return;
        }
        this.toastService.success('Заметка заказа сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.loadSelectedInvoice();
      },
      error: (err) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoiceId)) {
          this.toastService.error('Заметка не сохранена', apiErrorDetail(err, 'Не удалось сохранить заметку заказа'));
        }
      }
    });
  }

  trackMetric(_index: number, metric: { label: string }): string {
    return metric.label;
  }

  private async copyText(value: string | undefined | null, copiedKey: string, successTitle: string): Promise<void> {
    const text = (value ?? '').trim();
    if (!text) {
      this.toastService.error('Не скопировано', 'Нет текста для копирования');
      return;
    }
    if (await copyTextToClipboard(text)) {
      this.copied.set(copiedKey);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set('');
        }
      }, 1600);
      this.toastService.success(successTitle);
    } else {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  private loadSelectedInvoice(): void {
    const invoiceId = this.selectedAccount()?.currentInvoice?.id;
    const viewGeneration = this.invoiceViewGeneration;
    const readRun = ++this.invoiceReadRun;
    this.invoiceLoadSubscription?.unsubscribe();
    if (!invoiceId) {
      this.invoiceDetails.set(null);
      this.manualAttributionRequired.set(null);
      this.invoiceLoading.set(false);
      return;
    }
    this.invoiceLoading.set(true);
    this.invoiceLoadSubscription = this.commonBillingApi.invoice(invoiceId).subscribe({
      next: (details) => {
        if (!this.isCurrentInvoiceRead(readRun, viewGeneration, invoiceId)) {
          return;
        }
        this.invoiceDetails.set(details);
        this.loadManualAttributionMode(invoiceId);
        this.invoiceCardIndex.set(0);
        this.invoiceLoading.set(false);
      },
      error: (err) => {
        if (!this.isCurrentInvoiceRead(readRun, viewGeneration, invoiceId)) {
          return;
        }
        this.invoiceLoading.set(false);
        const message = apiErrorDetail(err);
        this.error.set(message);
        this.toastService.error('Счет не загрузился', message);
      }
    });
  }

  private expectedCurrentInvoiceId(): number | null {
    if (this.managerInvoiceDetailMode) {
      return this.requestedInvoiceId();
    }
    return this.selectedAccount()?.currentInvoice?.id ?? this.requestedInvoiceId();
  }

  private requestedInvoiceId(): number | null {
    if (this.requestedInvoiceReleased) {
      return null;
    }
    const value = Number(this.route.snapshot.queryParamMap.get('invoiceId') ?? 0);
    return Number.isSafeInteger(value) && value > 0 ? value : null;
  }

  private loadInvoiceByRequestedId(invoiceId: number): void {
    const viewGeneration = this.invoiceViewGeneration;
    const readRun = ++this.invoiceReadRun;
    this.invoiceLoadSubscription?.unsubscribe();
    this.invoiceLoading.set(true);
    this.invoiceLoadSubscription = this.commonBillingApi.invoice(invoiceId).subscribe({
      next: (details) => {
        if (!this.isCurrentInvoiceRead(readRun, viewGeneration, invoiceId)) {
          return;
        }
        this.invoiceDetails.set(details);
        this.loadManualAttributionMode(invoiceId);
        this.invoiceCardIndex.set(0);
        this.selectedAccountId.set(details.summary.accountId);
        this.accounts.update((accounts) => accounts.map((account) =>
          account.id === details.summary.accountId
            ? { ...account, currentInvoice: details.summary }
            : account
        ));
        this.applySelectedDraft();
        this.invoiceLoading.set(false);
      },
      error: (err) => {
        if (!this.isCurrentInvoiceRead(readRun, viewGeneration, invoiceId)) {
          return;
        }
        this.invoiceLoading.set(false);
        this.toastService.error('Счет не загрузился', apiErrorDetail(err));
      }
    });
  }

  private humanCommonInvoiceError(error: string): string {
    const normalized = error.toLowerCase();
    if (normalized.startsWith('disabled:')) {
      return 'Связь выключена, а неоплаченные заказы были отключены от общего счета. Проверьте, нужно ли включить связь заново или закрыть этот хвост в контроле.';
    }
    if (normalized.startsWith('empty:')) {
      return 'В общем счете не осталось активных неоплаченных заказов. Проверьте, не нужно ли закрыть пустой счет или убрать его из контроля.';
    }
    if (normalized.startsWith('merged_into:')) {
      return 'Позиции перенесены в другой общий счет. Откройте актуальную связь и закройте этот хвост после проверки.';
    }
    if (normalized.startsWith('whatsapp_group_missing')) {
      return 'Не найден чат WhatsApp для отправки общего счета. Проверьте связь компании с чатом или отправьте счет вручную.';
    }
    if (normalized.startsWith('paper_invoice_mode_switch_payment_detected:')) {
      return 'Банк обнаружил платёжное движение по прежней ссылке. Бумажный режим не включён: сначала нужно сверить банковскую оплату.';
    }
    if (normalized.startsWith('paper_invoice_mode_switch_retry:')
      || normalized.startsWith('paper_invoice_mode_switch_in_progress:')
      || normalized.startsWith('paper_invoice_mode_switch_state_changed:')) {
      return 'Прежняя банковская сессия ещё не закрыта однозначно. Оплата по ссылке остановлена в интерфейсе; повторите включение бумажного счёта после обновления.';
    }
    if (normalized.startsWith('manual_payment_tbank_reconciliation_in_progress:')
      || normalized.startsWith('manual_payment_tbank_reconciliation_retry:')) {
      return 'Банк пока не подтвердил безопасное закрытие ссылки. Деньги по прежним реквизитам не зачислены повторно; можно повторить автоматическую сверку.';
    }
    if (normalized.startsWith('manual_payment_tbank_payment_detected:')) {
      return 'Банк обнаружил движение денег по ссылке. Ручной перевод по прежним реквизитам не зачислен, чтобы не создать двойную оплату.';
    }
    if (normalized.includes('t-bank') || normalized.includes('tbank') || normalized.includes('payment')) {
      return 'Есть ошибка банковского платежа. Проверьте состояние оплаты в правой панели и повторите действие только после сверки.';
    }
    return `Ошибка общего счета: ${error}`;
  }

  private humanPaymentNotificationError(error: string): string {
    const normalized = error.toLowerCase();
    if (normalized.startsWith('whatsapp_group_missing')) {
      return 'Оплата прошла, но автоматическое сообщение в WhatsApp не ушло: у группы не заполнен groupId.';
    }
    if (normalized.startsWith('immediate_messages_disabled')) {
      return 'Оплата прошла, но автоматическое уведомление выключено в настройках сообщений.';
    }
    if (normalized.startsWith('notification_result_empty') || normalized.startsWith('notification_not_sent')) {
      return 'Оплата прошла, но система не получила подтверждение отправки уведомления.';
    }
    return `Оплата прошла, но уведомление не отправлено: ${error}`;
  }

  private commonInvoiceProblemSteps(): string[] {
    const notificationError = this.invoicePaymentNotificationError().toLowerCase();
    const commonError = this.invoiceProblemRaw().toLowerCase();
    if (notificationError.startsWith('whatsapp_group_missing')) {
      return [
        'Откройте чат компании и убедитесь, что это нужная WhatsApp-группа.',
        'Заполните groupId у этой группы в настройках связи, чтобы следующие уведомления уходили автоматически.',
        'Если клиент уже ждет сообщение, скопируйте текст уведомления и отправьте его вручную.',
        'Когда сообщение отправлено или groupId исправлен, нажмите "Уведомление обработано".'
      ];
    }
    if (notificationError.startsWith('immediate_messages_disabled')) {
      return [
        'Проверьте, почему отключены моментальные клиентские сообщения.',
        'Включите отправку или отправьте клиенту уведомление вручную.',
        'После ручного сообщения нажмите "Уведомление обработано".'
      ];
    }
    if (notificationError.startsWith('notification_result_empty') || notificationError.startsWith('notification_not_sent')) {
      return [
        'Откройте чат и проверьте, появилось ли сообщение об оплате.',
        'Если сообщения нет, отправьте его вручную через текст уведомления.',
        'После проверки нажмите "Уведомление обработано".'
      ];
    }
    if (notificationError) {
      return [
        'Проверьте чат или настройки отправки уведомлений.',
        'Отправьте клиенту сообщение вручную, если автоматическая отправка не сработала.',
        'После исправления нажмите "Уведомление обработано".'
      ];
    }
    if (this.attentionIsMigrationPaymentRegistry() && this.attentionHasPaymentInitCheck()) {
      return paymentInitNoPaymentInstructions();
    }
    if (this.attentionIsMigrationPaymentRegistry()) {
      return [
        'Не используйте повторную отправку или закрытие проверки: миграционный конфликт остается в карантине.',
        'Сохраните перечисленные идентификаторы и передайте их администратору для отдельной сверки в банке.'
      ];
    }
    if (this.attentionHasPaymentInitCheck()) {
      return paymentInitNoPaymentInstructions();
    }
    if (this.attentionHasManualTbankReconciliationRetry()) {
      return [
        'Убедитесь, что в чате есть чек перевода по прежним реквизитам.',
        'Нажмите «Повторить сверку оплаты» и снова укажите фактического получателя.',
        'Система сама проверит банк платежа; вручную менять платёжный маршрут не нужно.'
      ];
    }
    if (this.attentionHasManualTbankPaymentDetected()) {
      return [
        'Не закрывайте счёт ручной оплатой и не отправляйте клиенту новую ссылку.',
        'Сверьте платёж по банковской ссылке и перевод по прежним реквизитам.',
        'После определения лишнего платежа оформите возврат или отдельную административную корректировку.'
      ];
    }
    if (commonError.startsWith('whatsapp_group_missing')) {
      return [
        'Откройте чат компании и проверьте WhatsApp-группу.',
        'Заполните groupId у связи или отправьте счет вручную.',
        'После исправления повторите отправку счета.'
      ];
    }
    if (commonError.startsWith('disabled:') || commonError.startsWith('empty:') || commonError.startsWith('merged_into:')) {
      return [
        'Проверьте, что в счете действительно нет активных неоплаченных заказов.',
        'Если заказы перенесены или связь выключена намеренно, закройте технический хвост.'
      ];
    }
    return [];
  }

  private buildPaymentNotificationText(): string {
    const invoice = this.currentInvoice();
    if (!invoice) {
      return '';
    }
    const title = invoice.title || invoice.accountName || 'общему счету';
    const parts = [
      'Здравствуйте!',
      '',
      `Оплата по общему счету "${title}" прошла успешно.`,
      `Оплачено: ${this.formatKopecks(invoice.paidKopecks)}.`,
      `Готово заказов: ${invoice.paidOrders}/${invoice.totalOrders}.`
    ];
    if (invoice.publicUrl?.trim()) {
      parts.push('', `Ссылка на счет: ${invoice.publicUrl.trim()}`);
    }
    return parts.join('\n');
  }

  private invoiceAction(
    invoiceId: number,
    key: string,
    action: () => ReturnType<CommonBillingApi['invoice']>,
    successTitle: string,
    onSuccess?: (details: CommonInvoiceDetailsResponse) => void,
    /** Lets a window show the refusal itself: return true to keep the default toast away. */
    onError?: (message: string, status: number | undefined) => boolean
  ): void {
    const viewGeneration = this.invoiceViewGeneration;
    this.invalidateAccountLoad();
    this.mutating.set(key);
    action().subscribe({
      next: (details) => {
        if (!this.isCurrentInvoiceView(viewGeneration, invoiceId)) {
          return;
        }
        this.invoiceDetails.set(details);
        this.deliveryWatcher.watch(details, () => firstValueFrom(this.commonBillingApi.invoice(invoiceId)),
          updated => this.invoiceDetails.set(updated), () => this.isCurrentInvoiceView(viewGeneration, invoiceId));
        this.accounts.update((accounts) => accounts.map((account) => {
          if (account.currentInvoice?.id !== invoiceId) {
            return account;
          }
          return { ...account, currentInvoice: details.summary };
        }));
        this.mutating.set('');
        const deliveryWarning = key === 'send-invoice'
          ? (deliveryOperationMessage(details.delivery) ?? commonInvoiceDeliveryWarning(details.summary.lastError)) : null;
        if (key === 'send-invoice' && details.delivery?.status === 'SENT') {
          this.toastService.success('Отправка подтверждена');
        } else if (deliveryWarning) {
          this.toastService.warning(details.delivery ? 'Состояние отправки' : 'Отправка общего счета не подтверждена', deliveryWarning);
        } else if (successTitle) {
          this.toastService.success(successTitle);
        }
        onSuccess?.(details);
      },
      error: (err) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoiceId) && this.mutating() === key) {
          if (onError?.(apiErrorDetail(err, 'Действие со счетом не выполнено'), (err as { status?: number } | null)?.status)) {
            this.mutating.set('');
            return;
          }
          this.failMutation(err, 'Действие со счетом не выполнено');
        }
      }
    });
  }

  private loadManualAttributionMode(invoiceId: number): void {
    const viewGeneration = this.invoiceViewGeneration;
    this.manualAttributionRequired.set(null);
    this.commonManualPaymentApi.mode(invoiceId).subscribe({
      next: (mode) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoiceId)) {
          this.manualAttributionRequired.set(Boolean(mode.attributionRequired));
        }
      },
      error: () => {
        if (this.isCurrentInvoiceView(viewGeneration, invoiceId)) {
          // Mode-read failure is fail-closed: never expose a legacy action
          // that could silently credit the original recipient.
          this.manualAttributionRequired.set(true);
          this.toastService.error(
            'Режим расчётов не проверен',
            'Старое ручное подтверждение отключено до успешной проверки режима'
          );
        }
      }
    });
  }

  private invalidateInvoiceView(): void {
    this.invoiceViewGeneration += 1;
    this.invoiceReadRun += 1;
    this.invoiceLoadSubscription?.unsubscribe();
    this.invoiceLoadSubscription = undefined;
    this.invoiceLoading.set(false);
    this.mutating.set('');
    this.manualAttributionRequired.set(null);
  }

  private isCurrentAccountLoad(loadRun: number): boolean {
    return !this.destroyed && loadRun === this.accountLoadRun;
  }

  private invalidateAccountLoad(): void {
    this.accountLoadRun += 1;
    this.accountsLoadSubscription?.unsubscribe();
    this.accountsLoadSubscription = undefined;
    this.loading.set(false);
  }

  private isCurrentPageView(viewGeneration: number): boolean {
    return !this.destroyed && viewGeneration === this.invoiceViewGeneration;
  }

  private isCurrentInvoiceRead(readRun: number, viewGeneration: number, invoiceId: number): boolean {
    return readRun === this.invoiceReadRun && this.isCurrentInvoiceView(viewGeneration, invoiceId);
  }

  private isCurrentInvoiceView(viewGeneration: number, invoiceId: number): boolean {
    return !this.destroyed
      && viewGeneration === this.invoiceViewGeneration
      && this.desiredInvoiceId() === invoiceId;
  }

  private desiredInvoiceId(): number | null {
    return this.requestedInvoiceId() ?? this.selectedAccount()?.currentInvoice?.id ?? null;
  }

  private replaceAccount(updated: CommonBillingAccountResponse): void {
    this.accounts.update((accounts) => accounts.map((account) => account.id === updated.id ? updated : account));
  }

  private applySelectedDraft(): void {
    const account = this.selectedAccount();
    if (!account) {
      return;
    }
    this.draft.set({
      name: account.name ?? '',
      enabled: account.enabled,
      autoRepeatOrders: account.autoRepeatOrders,
      managerId: account.managerId == null ? '' : String(account.managerId),
      invoiceCompanyId: account.invoiceCompanyId == null ? '' : String(account.invoiceCompanyId)
    });
    this.clearCompanySearch();
  }

  private enabledCompanyIds(account: CommonBillingAccountResponse): number[] {
    return (account.companies ?? [])
      .filter((company) => company.enabled)
      .map((company) => company.companyId);
  }

  private pluralRu(value: number, one: string, few: string, many: string): string {
    const mod10 = Math.abs(value) % 10;
    const mod100 = Math.abs(value) % 100;
    if (mod10 === 1 && mod100 !== 11) {
      return one;
    }
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
      return few;
    }
    return many;
  }

  private optionalNumber(value: string): number | null {
    const numeric = Number(value);
    return Number.isInteger(numeric) && numeric > 0 ? numeric : null;
  }

  private clearCompanySearch(): void {
    this.companySearchRun += 1;
    this.companySearch.set('');
    this.companySearchResults.set([]);
    this.companySearchError.set('');
    this.companySearchLoading.set(false);
    if (this.companySearchTimer != null) {
      window.clearTimeout(this.companySearchTimer);
      this.companySearchTimer = null;
    }
  }

  private failMutation(err: unknown, fallback: string): void {
    const message = apiErrorDetail(err, fallback);
    this.mutating.set('');
    if ((err as { status?: number } | null)?.status === 423) this.toastService.info('Ожидание банка', message);
    else this.toastService.error(fallback, message);
  }
}
