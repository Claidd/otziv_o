import { DatePipe } from '@angular/common';
import { Component, ElementRef, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  CommonBillingAccountResponse,
  CommonBillingApi,
  CommonInvoiceDetailsResponse,
  CommonInvoiceOrderResponse,
  CommonInvoicePaymentRefResponse,
  CommonInvoicePaymentRouteChangeTarget,
  CommonInvoiceSummaryResponse,
  ManualPaymentConfirmationRequest
} from '../../../core/common-billing.api';
import {
  CommonManualPaymentAttributionApi,
  type CommonManualPaymentMode
} from '../../../core/common-manual-payment-attribution.api';
import { AuthService } from '../../../core/auth.service';
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
import { ManagerBoardOrderFacade } from '../../manager/manager-board-order.facade';
import { ManagerOrderCardComponent } from '../../manager/manager-order-card.component';
import type { ManagerOrderEditDraftChange } from '../../manager/manager-order-edit-modal.component';
import { ManagerOrderEditModalComponent } from '../../manager/manager-order-edit-modal.component';
import { CommonManualPaymentAttributionModalComponent } from './common-manual-payment-attribution-modal.component';
import {
  StatusAction,
  managerErrorMessage,
  managerOrderActions,
  managerOrderReviewCopyText
} from '../../manager/manager-board.config';

type AccountDraft = {
  name: string;
  enabled: boolean;
  autoRepeatOrders: boolean;
  managerId: string;
  invoiceCompanyId: string;
};

type DraftCompany = {
  id: number;
  title: string;
  city?: string;
  status?: string;
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

export const PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL = 'В T‑Bank оплаты нет — продолжить';

export function paymentInitNoPaymentInstructions(): string[] {
  return [
    'В T-Бизнесе откройте «Интернет-эквайринг → Операции». В поле «Номер заказа» вводите только OrderId.',
    'PaymentId — это отдельный идентификатор платежа. Не вводите его в поле «Номер заказа»: переключите тип поиска на идентификатор платежа, если такой фильтр доступен, либо сверьте PaymentId в карточке найденной операции.',
    'Если найден активный платёж — не создавайте новую ссылку и дождитесь его итога.',
    'Если найден успешный платёж или операция, которой нужна отмена/возврат, — не нажимайте кнопку ниже. Обработайте операцию по её фактическому статусу в разделе платежей.',
    `Только если ни один платёж не активен, не успешен и не требует отмены/возврата, нажмите «${PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL}».`
  ];
}

export function paymentInitNoPaymentActionLabel(): string {
  return `«${PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL}» подтверждает только отсутствие оплаты `
    + 'по перечисленным OrderId и PaymentId в T‑Bank и закрывает техническую ошибку. '
    + 'Счёт не будет отмечен оплаченным и не перейдёт в статус «Не оплачено». '
    + 'Если клиент оплатил переводом на обычную карту, после этого отдельным действием «Оплачено» '
    + 'зафиксируйте перевод и добавьте комментарий или чек.';
}

export function paymentInitNoPaymentConfirmation(evidenceText: string): string {
  return 'РЕЗУЛЬТАТ ПРОВЕРКИ T‑BANK: ПО УКАЗАННЫМ ID ОПЛАТЫ НЕТ.\n\n'
    + 'Подтвердите, что вы проверили в T‑Bank ВСЕ перечисленные OrderId и PaymentId и ни один платёж по ним '
    + 'не активен, не успешен и не требует отмены или возврата.\n\n'
    + 'Если такой платёж найден, нажмите «Отмена» и не продолжайте.\n\n'
    + 'Это действие означает ТОЛЬКО отсутствие оплаты по перечисленным T‑Bank ID. '
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

@Component({
  selector: 'app-common-billing',
  imports: [
    AdminLayoutComponent,
    CommonManualPaymentAttributionModalComponent,
    DatePipe,
    FormsModule,
    LoadErrorCardComponent,
    ManagerOrderCardComponent,
    ManagerOrderEditModalComponent,
    MobileBottomPagerComponent,
    RouterLink
  ],
  templateUrl: './common-billing.component.html',
  styleUrl: './common-billing.component.scss'
})
export class CommonBillingComponent implements OnDestroy {
  readonly paymentInitNoPaymentButtonLabel = PAYMENT_INIT_NO_PAYMENT_BUTTON_LABEL;
  @ViewChild('invoiceOrderCardsViewport') private invoiceOrderCardsElement?: ElementRef<HTMLElement>;

  private readonly commonBillingApi = inject(CommonBillingApi);
  private readonly commonManualPaymentApi = inject(CommonManualPaymentAttributionApi);
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
  readonly selectedAccountId = signal<number | null>(null);
  readonly invoiceDetails = signal<CommonInvoiceDetailsResponse | null>(null);
  readonly loading = signal(false);
  readonly invoiceLoading = signal(false);
  readonly error = signal('');
  readonly mutating = signal('');
  readonly copied = signal('');
  readonly manualAttributionRequired = signal<boolean | null>(null);
  readonly manualAttributionMode = signal<CommonManualPaymentMode | null>(null);
  readonly companySearch = signal('');
  readonly companySearchResults = signal<CompanyCardItem[]>([]);
  readonly companySearchLoading = signal(false);
  readonly companySearchError = signal('');
  readonly draftCompanies = signal<DraftCompany[]>([]);
  readonly draft = signal<AccountDraft>({
    name: '',
    enabled: true,
    autoRepeatOrders: true,
    managerId: '',
    invoiceCompanyId: ''
  });
  readonly managerInvoiceDetailMode = this.router.url.startsWith('/manager/common-billing');
  private readonly orderFacade = new ManagerBoardOrderFacade({
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
  readonly attentionError = computed(() => (this.currentInvoice()?.lastError ?? '').trim().toLowerCase());
  readonly attentionHasLatePayment = computed(() => {
    const error = this.attentionError();
    return error.startsWith('late_tbank_payment') || error.startsWith('late_payment_');
  });
  readonly attentionHasFinalCancelFailure = computed(() => this.attentionError().startsWith('payment_cancel_failed_final'));
  readonly attentionHasStandaloneRouteConflict = computed(() => this.attentionError().startsWith('standalone_payment_route_conflict'));
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
  readonly invoiceProblemRaw = computed(() => (this.currentInvoice()?.lastError ?? '').trim());
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
    switch (invoice.status) {
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
      case 'INVOICED':
      case 'REMINDER':
      case 'PARTIALLY_PAID':
        return 'Счет ожидает оплаты. Проверьте, не завис ли платеж или напоминание.';
      default:
        return '';
    }
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
    if (this.invoiceNeedsAttention()) {
      return 'После проверки используйте зеленое действие ниже или повторите обработку.';
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
  readonly attentionRetryEnabled = computed(() => this.invoiceNeedsAttention()
    && !this.attentionRequiresManualCheck()
    && !this.attentionHasStandaloneRouteConflict()
    && !this.invoiceProblemRaw().toLowerCase().startsWith('paper_invoice_mode_switch_'));
  readonly attentionResolveEnabled = computed(() => {
    return this.invoiceNeedsAttention()
      && !this.attentionRequiresManualCheck()
      && !this.attentionHasStandaloneRouteConflict()
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
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly canMarkPaid = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && (
          ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'UNPAID'].includes(invoice.status)
          || (invoice.status === 'COLLECTING' && invoice.totalOrders > 0 && invoice.readyOrders >= invoice.totalOrders)
        )
    );
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
  readonly canMarkUnpaid = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
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
      this.invalidateInvoiceView();
      this.load();
    });
  }

  ngOnDestroy(): void {
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
    this.invalidateInvoiceView();
    this.selectedAccountId.set(account.id);
    this.invoiceDetails.set(null);
    this.applySelectedDraft();
    this.loadSelectedInvoice();
  }

  createAccount(): void {
    const draft = this.draft();
    if (!draft.name.trim() || this.mutating()) {
      return;
    }

    const existingAccount = this.accounts().find((account) =>
      account.enabled && this.normalizedName(account.name) === this.normalizedName(draft.name)
    );
    if (existingAccount) {
      this.invalidateInvoiceView();
      this.selectedAccountId.set(existingAccount.id);
      this.applySelectedDraft();
      this.loadSelectedInvoice();
      this.toastService.success('Открыта существующая связь с таким названием');
      return;
    }

    const viewGeneration = this.invoiceViewGeneration;
    const mutationKey = 'create';
    this.invalidateAccountLoad();
    this.mutating.set(mutationKey);
    this.commonBillingApi.createAccount({
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: this.optionalNumber(draft.managerId),
      invoiceCompanyId: this.optionalNumber(draft.invoiceCompanyId),
      companyIds: this.draftCompanies().map((company) => company.id)
    }).subscribe({
      next: (account) => {
        if (!this.isCurrentPageView(viewGeneration)) {
          return;
        }
        this.accounts.update((accounts) => [account, ...accounts.filter((item) => item.id !== account.id)]);
        this.selectedAccountId.set(account.id);
        this.applySelectedDraft();
        this.loadSelectedInvoice();
        this.mutating.set('');
        this.toastService.success('Общий плательщик создан');
      },
      error: (err) => {
        if (this.isCurrentPageView(viewGeneration) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось создать общего плательщика');
        }
      }
    });
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
    const account = this.selectedAccount();
    if (account) {
      this.addCompany(company);
      return;
    }

    this.draftCompanies.update((companies) => [
      ...companies,
      {
        id: company.id,
        title: company.title,
        city: company.city,
        status: company.status
      }
    ]);
    this.clearCompanySearch();
  }

  removeDraftCompany(companyId: number): void {
    this.draftCompanies.update((companies) => companies.filter((company) => company.id !== companyId));
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
      this.manualAttributionMode.set('STANDARD');
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

  changePaperInvoiceMode(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !this.canManagePaperInvoices() || this.mutating()) {
      return;
    }
    const paperEnabled = invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE';
    const nextMode = paperEnabled ? 'AUTO_ROUTING' : 'OWNER_PAPER_INVOICE';
    const confirmation = paperEnabled
      ? 'Вернуть автоматическое распределение специалист → менеджер → владелец? Продолжайте только если бумажный счёт ещё не отправлялся и оплаты по нему нет.'
      : 'Включить бумажный счёт владельца? Система проверит прежнюю T‑Bank-ссылку и отменит её только при подтверждённом отсутствии оплаты. Текущий безопасный резерв будет освобождён, а будущие циклы этого общего счёта тоже станут бумажными. Продолжайте только если клиент ещё не платил.';
    if (!window.confirm(confirmation)) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'change-paper-invoice-mode',
      () => this.commonBillingApi.changeInvoicePaymentMode(invoice.id, nextMode),
      paperEnabled ? 'Автоматическое распределение восстановлено' : 'Режим бумажного счёта включён'
    );
  }

  changeCommonInvoicePaymentRoute(): void {
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
        if (!context.canChange) {
          this.toastService.error('Способ оплаты нельзя изменить', context.blockReason || 'Нужна ручная сверка');
          return;
        }
        const target: CommonInvoicePaymentRouteChangeTarget = context.currentTarget === 'OWNER_TBANK'
          ? 'EMPLOYEE_REQUISITES'
          : 'OWNER_TBANK';
        const destination = target === 'EMPLOYEE_REQUISITES'
          ? 'реквизиты специалиста или менеджера'
          : 'ссылку T‑Bank владельца';
        const currentRecipient = context.currentRecipient?.trim()
          ? ` Получатель сейчас: ${context.currentRecipient}.`
          : '';
        if (!window.confirm(
          `Сменить способ оплаты на ${destination}?${currentRecipient} `
          + 'Старый безопасный резерв будет освобождён, новый маршрут создан заново, '
          + 'а клиенту сразу уйдёт обновлённое сообщение. Продолжайте только если клиент ещё не платил.'
        )) {
          return;
        }
        this.invoiceAction(
          invoice.id,
          'change-common-payment-route',
          () => this.commonBillingApi.changeCommonInvoicePaymentRoute(
            invoice.id,
            target,
            context.paymentEvidenceToken
          ),
          target === 'EMPLOYEE_REQUISITES'
            ? 'Общий счёт переведён на реквизиты сотрудника'
            : 'Общий счёт переведён на ссылку T‑Bank владельца'
        );
      },
      error: (err) => {
        if (this.isCurrentPageView(viewGeneration) && this.mutating() === mutationKey) {
          this.failMutation(err, 'Не удалось проверить смену способа оплаты');
        }
      }
    });
  }

  markPaperInvoiceIssued(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
      || invoice.paperInvoiceIssuedAt || !this.canManagePaperInvoices() || this.mutating()) {
      return;
    }
    if (!window.confirm('Подтвердить, что бумажный счёт уже отправлен клиенту? После этой отметки начнутся автоматические напоминания.')) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'paper-invoice-issued',
      () => this.commonBillingApi.markPaperInvoiceIssued(invoice.id),
      'Бумажный счёт отмечен как отправленный'
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
    const confirmed = window.confirm('Распределить поздний T-Bank платеж по неоплаченным заказам общего счета?');
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
    const confirmed = window.confirm('Подтвердить, что старая T-Bank ссылка проверена вручную и больше не требует действий?');
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
      : '\n\nИдентификаторы платежа не сохранены: проверьте операцию по счету вручную в T-Bank.';
    const confirmed = window.confirm(paymentInitNoPaymentConfirmation(evidenceText));
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'confirm-payment-init-check',
      () => this.commonBillingApi.confirmPaymentInitCheck(invoice.id, snapshot.evidenceToken),
      'В T‑Bank оплаты нет: техническая проверка закрыта'
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
        return 'T-Bank';
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

  paymentRouteLabel(invoice: CommonInvoiceSummaryResponse): string {
    if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return 'Бумажный счёт владельца';
    }
    const profile = invoice.paymentRouteProfileName?.trim();
    switch (invoice.paymentRouteType) {
      case 'TBANK_LINK':
        return profile ? `T-Bank · ${profile}` : 'T-Bank';
      case 'MANUAL_EXTERNAL_LINK':
        return invoice.paymentRouteManualTaskId
          ? `Внешняя ссылка · задание #${invoice.paymentRouteManualTaskId}`
          : 'Внешняя ссылка';
      case 'MANUAL_MOBILE_BANK':
        return invoice.paymentRouteManualTaskId
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
      `${title}\n\nВведите комментарий (например: «сверено по выписке»). Если есть только чек — оставьте пустым.`
    );
    if (commentValue === null) {
      return null;
    }
    const receiptValue = window.prompt(
      'Ссылка на чек или платёжный документ (необязательно, если заполнен комментарий):'
    );
    if (receiptValue === null) {
      return null;
    }
    const evidence = {
      comment: commentValue.trim(),
      receiptUrl: receiptValue.trim()
    };
    if (!evidence.comment && !evidence.receiptUrl) {
      this.toastService.error(
        'Оплата не подтверждена',
        'Для ручной оплаты обязательно укажите комментарий или ссылку на чек.'
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
    if (!invoice || invoice.status === 'NEEDS_ATTENTION' || !order.detachable || this.mutating()) {
      return;
    }

    const target = order.paid
      ? 'Заказ отмечен оплаченным внутри общего счета, поэтому будет закрыт отдельно как оплаченный.'
      : `Заказ вернется в статус "${order.originalOrderStatus || 'Опубликовано'}".`;
    const confirmed = window.confirm(`Отключить заказ №${order.orderId} от общего счета? ${target}`);
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

  trackDraftCompany(_index: number, company: DraftCompany): number {
    return company.id;
  }

  trackCompanySearch(_index: number, company: CompanyCardItem): number {
    return company.id;
  }

  companyAlreadySelected(companyId: number): boolean {
    const account = this.selectedAccount();
    if (account) {
      return (account.companies ?? []).some((company) => company.companyId === companyId && company.enabled);
    }
    return this.draftCompanies().some((company) => company.id === companyId);
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
      return 'T-Bank обнаружил платёжное движение по прежней ссылке. Бумажный режим не включён: сначала нужно сверить банковскую оплату.';
    }
    if (normalized.startsWith('paper_invoice_mode_switch_retry:')
      || normalized.startsWith('paper_invoice_mode_switch_in_progress:')
      || normalized.startsWith('paper_invoice_mode_switch_state_changed:')) {
      return 'Прежняя T-Bank-сессия ещё не закрыта однозначно. Оплата по ссылке остановлена в интерфейсе; повторите включение бумажного счёта после обновления.';
    }
    if (normalized.includes('t-bank') || normalized.includes('tbank') || normalized.includes('payment')) {
      return 'Есть ошибка платежа или T-Bank. Проверьте состояние оплаты в правой панели и повторите действие только после сверки.';
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
        'Сохраните перечисленные идентификаторы и передайте их администратору для отдельной сверки T-Bank.'
      ];
    }
    if (this.attentionHasPaymentInitCheck()) {
      return paymentInitNoPaymentInstructions();
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
    successTitle: string
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
        this.accounts.update((accounts) => accounts.map((account) => {
          if (account.currentInvoice?.id !== invoiceId) {
            return account;
          }
          return { ...account, currentInvoice: details.summary };
        }));
        this.mutating.set('');
        this.toastService.success(successTitle);
      },
      error: (err) => {
        if (this.isCurrentInvoiceView(viewGeneration, invoiceId) && this.mutating() === key) {
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
    this.draftCompanies.set([]);
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

  private normalizedName(value: string | null | undefined): string {
    return (value ?? '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ru-RU');
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
    this.toastService.error(fallback, message);
  }
}
