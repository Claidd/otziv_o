import { Component, HostListener, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { CompanyDeepReportLaunchService } from '../../core/company-deep-report-launch.service';
import { CompanyCreateResult, CompanyCreateSource } from '../../core/company-create.api';
import {
  CommonBillingApi,
  type CommonInvoiceDetailsResponse,
  type ManualPaymentConfirmationRequest
} from '../../core/common-billing.api';
import { CommonManualPaymentAttributionApi } from '../../core/common-manual-payment-attribution.api';
import { MetricSnapshotApi } from '../../core/metric-snapshot.api';
import { PaymentsApi } from '../../core/payments.api';
import {
  CompanyCardItem,
  CompanyChatBindingRepair,
  ManagerApi,
  ManagerBoard,
  ManagerMetric,
  ManagerOverdueOrders,
  ManagerOverdueStatus,
  ManagerOption,
  ManagerPage,
  ManagerSection,
  type PaymentRouteChangeTarget,
  OrderCardItem
} from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { CompanyCreateModalComponent } from '../../shared/company-create-modal.component';
import { DailyProgressStripComponent } from '../../shared/daily-progress-strip.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { MobileBottomPagerComponent } from '../../shared/mobile/mobile-bottom-pager.component';
import { MobileNavIntentService } from '../../shared/mobile/mobile-nav-intent.service';
import { MobileStatusSheetComponent } from '../../shared/mobile/mobile-status-sheet.component';
import {
  MobileStatusSliderComponent,
  type MobileStatusItem
} from '../../shared/mobile/mobile-status-slider.component';
import { PersonalRemindersComponent } from '../../shared/personal-reminders.component';
import { phoneDigits } from '../../shared/phone-format';
import { safeHttpsExternalUrl } from '../../shared/external-navigation';
import { ToastService } from '../../shared/toast.service';
import {
  DEFAULT_MANAGER_COMPANY_STATUSES,
  DEFAULT_MANAGER_ORDER_STATUSES,
  EMPTY_MANAGER_COMPANY_PAGE,
  EMPTY_MANAGER_ORDER_PAGE,
  MANAGER_COMPANY_ACTIONS,
  MANAGER_HISTORY_STATE_KEY,
  MANAGER_MOBILE_NAV_LINKS,
  MANAGER_ORDER_ACTIONS,
  MANAGER_PAGE_SIZE_OPTIONS,
  MANAGER_SECTIONS,
  ManagerChatBotInviteKind,
  ManagerHistoryView,
  MobileNavLink,
  PromoItem,
  SelectedCompany,
  StatusAction,
  managerBoardTitle,
  managerChatBotInviteKind,
  managerChatBotInviteUrl,
  managerErrorMessage,
  managerLayoutTitle,
  managerMobileStatusItems,
  managerOrderActions,
  managerOrderReviewCopyText,
  managerPromoItems,
  managerStatusOptionLabel,
  trackManagerCompany,
  trackManagerMetric,
  trackManagerOrder,
  trackManagerStatus
} from './manager-board.config';
import { ManagerBoardActionFacade } from './manager-board-action.facade';
import { CommonManualPaymentAttributionModalComponent } from '../admin/common-billing/common-manual-payment-attribution-modal.component';
import {
  ManagerCommonInvoicePaymentFacade,
  type ManagerCommonManualPaymentContext
} from './manager-common-invoice-payment.facade';
import { ManagerCompanyCardComponent } from './manager-company-card.component';
import type {
  ManagerCompanyBillingDraftChange,
  ManagerCompanyEditDraftChange,
  ManagerCompanyFilialUpdateRequest
} from './manager-company-edit-modal.component';
import { ManagerCompanyEditModalComponent } from './manager-company-edit-modal.component';
import { ManagerBoardCompanyFacade } from './manager-board-company.facade';
import {
  managerReadHistoryView,
  managerReadQueryView,
  managerViewQueryParams,
  managerWithHistoryState
} from './manager-board.history';
import { ManagerBoardOrderFacade } from './manager-board-order.facade';
import { ManagerOrderCardComponent } from './manager-order-card.component';
import type { ManagerOrderEditDraftChange } from './manager-order-edit-modal.component';
import { ManagerOrderEditModalComponent } from './manager-order-edit-modal.component';
import type { ManagerCreateOrderDraftChange } from './manager-order-create-modal.component';
import { ManagerOrderCreateModalComponent } from './manager-order-create-modal.component';
import {
  ManagerManualCardPaymentModalComponent,
  type ManagerManualCardPaymentCompleted
} from './manager-manual-card-payment-modal.component';

type CompanyCreateContext = {
  source: CompanyCreateSource;
  leadId: number | null;
};

type ChatBotLinkPlatform = Exclude<ManagerChatBotInviteKind, null>;

type ChatBotLinkPoll = {
  startedAt: number;
  platform: ChatBotLinkPlatform;
};

type ChatLinkEditor = {
  companyId: number;
  title: string;
};

@Component({
  selector: 'app-manager-board',
  imports: [
    AdminLayoutComponent,
    CompanyCreateModalComponent,
    DailyProgressStripComponent,
    FormsModule,
    LoadErrorCardComponent,
    MobileBottomPagerComponent,
    MobileStatusSheetComponent,
    MobileStatusSliderComponent,
    ManagerCompanyCardComponent,
    ManagerCompanyEditModalComponent,
    ManagerOrderCardComponent,
    ManagerOrderEditModalComponent,
    ManagerOrderCreateModalComponent,
    ManagerManualCardPaymentModalComponent,
    CommonManualPaymentAttributionModalComponent,
    PersonalRemindersComponent,
    RouterLink
  ],
  templateUrl: './manager-board.component.html',
  styleUrl: './manager-board.component.scss'
})
export class ManagerBoardComponent implements OnDestroy {
  private readonly historyStateKey = MANAGER_HISTORY_STATE_KEY;
  private readonly managerApi = inject(ManagerApi);
  private readonly paymentsApi = inject(PaymentsApi);
  private readonly commonBillingApi = inject(CommonBillingApi);
  private readonly commonManualPaymentApi = inject(CommonManualPaymentAttributionApi);
  private readonly metricSnapshotApi = inject(MetricSnapshotApi);
  private readonly toastService = inject(ToastService);
  private readonly companyDeepReportLaunch = inject(CompanyDeepReportLaunchService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly mobileNavIntent = inject(MobileNavIntentService);
  private readonly emptyCompanyPage = EMPTY_MANAGER_COMPANY_PAGE;
  private readonly emptyOrderPage = EMPTY_MANAGER_ORDER_PAGE;
  private readonly overdueAlertStorageKeyPrefix = 'otziv-manager-overdue-alert:v2';
  private readonly chatBotLinkPollDelayMs = 8000;
  private readonly chatBotLinkPollTimeoutMs = 90000;
  private readonly searchDelayMs = 500;
  private readonly chatBotLinkPolls = new Map<number, ChatBotLinkPoll>();
  private readonly chatBotLinkPollTimers = new Map<number, number>();
  private chatBotLinkRefreshInFlight = false;
  private searchTimer: number | null = null;
  private lastMobileNavIntentStamp = 0;
  private boardLoadEpoch = 0;

  readonly sections = MANAGER_SECTIONS;
  readonly companyActions = MANAGER_COMPANY_ACTIONS;
  readonly allOrderActions = MANAGER_ORDER_ACTIONS;
  readonly pageSizeOptions = MANAGER_PAGE_SIZE_OPTIONS;
  readonly workersRoute = '/worker';
  readonly mobileNavLinks: MobileNavLink[] = MANAGER_MOBILE_NAV_LINKS;

  readonly board = signal<ManagerBoard | null>(null);
  readonly activeSection = signal<ManagerSection>('companies');
  readonly companyStatus = signal('Все');
  readonly orderStatus = signal('Все');
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly mobileMenuOpen = signal(false);
  readonly mobileStatusSheetOpen = signal(false);
  readonly selectedCompany = signal<SelectedCompany | null>(null);
  readonly selectedManagerId = signal<number | null>(null);
  readonly selectedControl = signal<string | null>(null);
  readonly companyCreateContext = signal<CompanyCreateContext | null>(null);
  readonly overdueOrders = signal<ManagerOverdueOrders | null>(null);
  readonly overdueModalOpen = signal(false);
  readonly chatLinkEditor = signal<ChatLinkEditor | null>(null);
  readonly chatLinkDraft = signal('');
  readonly chatLinkSaving = signal(false);

  readonly manualCardPaymentOrder = signal<OrderCardItem | null>(null);
  readonly commonManualPayment = signal<ManagerCommonManualPaymentContext | null>(null);
  private readonly companyFacade = new ManagerBoardCompanyFacade({
    managerApi: this.managerApi,
    commonBillingApi: this.commonBillingApi,
    toastService: this.toastService,
    loadBoard: () => this.loadBoard(),
    patchBoard: (updater) => this.patchBoard(updater),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback)
  });
  private readonly orderFacade = new ManagerBoardOrderFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    loadBoard: () => this.loadBoard(),
    patchBoard: (updater) => this.patchBoard(updater),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback),
    openCreatedCompanyOrders: (result) => this.openCreatedCompanyOrders(result.companyId, result.companyTitle)
  });
  private readonly actionFacade = new ManagerBoardActionFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    mutationKey: this.mutationKey,
    loadBoard: () => this.loadBoard(),
    patchBoard: (updater) => this.patchBoard(updater),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback),
    canOverrideActiveBankPayment: () => this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']),
    openManualCardPayment: (order) => this.manualCardPaymentOrder.set(order)
  });
  private readonly commonInvoicePaymentFacade = new ManagerCommonInvoicePaymentFacade({
    attributionApi: this.commonManualPaymentApi,
    commonBillingApi: this.commonBillingApi,
    mutationKey: this.mutationKey,
    requestLegacyEvidence: (invoiceId) => this.requestCommonInvoiceManualPaymentEvidence(invoiceId),
    openAttribution: (context) => this.commonManualPayment.set(context),
    completed: (details, order) => this.completeCommonInvoicePaid(details, order),
    failed: (title, message) => this.toastService.error(title, message),
    errorMessage: (error, fallback) => this.errorMessage(error, fallback)
  });
  readonly editCompany = this.companyFacade.editCompany;
  readonly editDraft = this.companyFacade.editDraft;
  readonly editLoading = this.companyFacade.editLoading;
  readonly editSaving = this.companyFacade.editSaving;
  readonly editError = this.companyFacade.editError;
  readonly editDeleteKey = this.companyFacade.editDeleteKey;
  readonly companyBillingAccounts = this.companyFacade.billingAccounts;
  readonly companyBillingSelectedAccountId = this.companyFacade.billingSelectedAccountId;
  readonly companyBillingDraft = this.companyFacade.billingDraft;
  readonly companyBillingLoading = this.companyFacade.billingLoading;
  readonly companyBillingError = this.companyFacade.billingError;
  readonly companyBillingMutating = this.companyFacade.billingMutating;
  readonly companyBillingSearch = this.companyFacade.billingCompanySearch;
  readonly companyBillingSearchResults = this.companyFacade.billingCompanySearchResults;
  readonly companyBillingSearchLoading = this.companyFacade.billingCompanySearchLoading;
  readonly companyBillingSearchError = this.companyFacade.billingCompanySearchError;
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
  readonly createOrderPayload = this.orderFacade.createOrderPayload;
  readonly createOrderDraft = this.orderFacade.createOrderDraft;
  readonly createOrderLoading = this.orderFacade.createOrderLoading;
  readonly createOrderSaving = this.orderFacade.createOrderSaving;
  readonly createOrderError = this.orderFacade.createOrderError;
  readonly selectedCreateOrderProduct = this.orderFacade.selectedCreateOrderProduct;
  readonly createOrderTotal = this.orderFacade.createOrderTotal;

  readonly currentCompanies = computed(() => this.board()?.companies.content ?? []);
  readonly currentOrders = computed(() => this.board()?.orders.content ?? []);
  readonly currentPage = computed<ManagerPage<CompanyCardItem | OrderCardItem>>(() => {
    if (this.activeSection() === 'companies') {
      return this.board()?.companies ?? this.emptyCompanyPage;
    }

    return this.board()?.orders ?? this.emptyOrderPage;
  });
  readonly activeStatus = computed(() => {
    return this.activeSection() === 'companies' ? this.companyStatus() : this.orderStatus();
  });
  readonly companyStatusOptions = computed(() => {
    return this.board()?.companyStatuses ?? DEFAULT_MANAGER_COMPANY_STATUSES;
  });
  readonly orderStatusOptions = computed(() => {
    return this.board()?.orderStatuses ?? DEFAULT_MANAGER_ORDER_STATUSES;
  });
  readonly statusOptions = computed(() => {
    return this.activeSection() === 'companies' ? this.companyStatusOptions() : this.orderStatusOptions();
  });
  readonly sortTitle = computed(() => this.sortDirection() === 'desc'
    ? 'Сначала давно без изменений'
    : 'Сначала недавно измененные'
  );
  readonly title = computed(() => {
    return managerBoardTitle(this.activeSection(), this.activeStatus(), this.selectedCompany());
  });
  readonly layoutTitle = computed(() => {
    return managerLayoutTitle(this.activeSection(), this.activeStatus(), this.selectedCompany());
  });
  readonly promoItems = computed<PromoItem[]>(() => {
    return managerPromoItems(this.activeSection(), this.board()?.promoTexts ?? []);
  });
  readonly metrics = computed(() => {
    return (this.board()?.metrics ?? []).filter((metric) => metric.section === this.activeSection());
  });
  readonly mobileStatusItems = computed<MobileStatusItem[]>(() => managerMobileStatusItems(
    this.activeSection(),
    this.statusOptions(),
    this.board()?.metrics ?? [],
    this.currentPage().totalElements
  ));
  readonly mobileSearchPlaceholder = computed(() => this.activeSection() === 'companies'
    ? 'Компания, телефон, город'
    : 'Заказ, компания, филиал'
  );

  constructor() {
    const routeSection = this.routeManagerSection();
    const queryView = managerReadQueryView(this.route.snapshot.queryParamMap, routeSection);
    const restoredView = managerReadHistoryView(window.history.state, this.historyStateKey);
    if (queryView) {
      this.applyHistoryView(queryView);
      this.replaceCurrentHistoryState();
    } else if (restoredView) {
      this.applyHistoryView(restoredView);
    } else {
      this.replaceCurrentHistoryState();
    }

    this.applyInitialMobileNavIntent();
    effect(() => this.handleMobileNavIntent());

    this.loadBoard();
    this.loadDailyOverdueReminder();
  }

  ngOnDestroy(): void {
    this.boardLoadEpoch += 1;
    this.clearSearchTimer();
    for (const timer of this.chatBotLinkPollTimers.values()) {
      window.clearTimeout(timer);
    }
    this.chatBotLinkPollTimers.clear();
    this.chatBotLinkPolls.clear();
  }

  @HostListener('window:popstate', ['$event'])
  restoreHistoryState(event: PopStateEvent): void {
    const view = managerReadHistoryView(event.state, this.historyStateKey);

    if (!view) {
      return;
    }

    this.applyHistoryView(view);
    this.loadBoard();
  }

  @HostListener('window:focus')
  refreshPendingChatBotLinks(): void {
    if (this.chatBotLinkPolls.size > 0) {
      this.reloadBoardForChatBotLinks();
    }
  }

  loadBoard(): void {
    const requestId = ++this.boardLoadEpoch;
    this.loading.set(true);
    this.error.set(null);

    this.managerApi.getBoard({
      section: this.activeSection(),
      status: this.activeStatus(),
      keyword: this.keyword(),
      companyId: this.activeSection() === 'orders' ? this.selectedCompany()?.id : undefined,
      managerId: this.activeSection() === 'orders' ? this.selectedManagerId() : null,
      control: this.activeSection() === 'orders' ? this.selectedControl() : null,
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection()
    }).subscribe({
      next: (board) => {
        if (requestId !== this.boardLoadEpoch) {
          return;
        }
        this.board.set(board);
        this.loading.set(false);
      },
      error: (err) => {
        if (requestId !== this.boardLoadEpoch) {
          return;
        }
        const message = this.errorMessage(err, 'Не удалось загрузить раздел менеджера');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Менеджер не загрузился', message);
      }
    });
  }

  setSection(section: ManagerSection): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set(section);
    this.selectedCompany.set(null);
    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric(section, section === 'companies' ? this.companyStatus() : this.orderStatus()));
  }

  setStatus(status: string): void {
    this.replaceCurrentHistoryState();
    const section = this.activeSection();
    if (this.activeSection() === 'companies') {
      this.companyStatus.set(status);
    } else {
      this.orderStatus.set(status);
    }

    this.selectedCompany.set(null);
    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric(section, status));
  }

  handleTopMenu(value: string): void {
    if (!value) {
      return;
    }

    if (value === 'leads') {
      void this.router.navigate(['/leads']);
      return;
    }

    if (value.startsWith('href:')) {
      window.location.href = value.slice(5);
      return;
    }

    if (value.startsWith('route:')) {
      this.mobileMenuOpen.set(false);
      void this.router.navigateByUrl(value.slice(6));
      return;
    }

    const [section, status = 'Все'] = value.split(':');

    if (section === 'companies') {
      this.replaceCurrentHistoryState();
      this.activeSection.set('companies');
      this.companyStatus.set(status);
      this.selectedCompany.set(null);
      this.pageNumber.set(0);
      this.mobileMenuOpen.set(false);
      this.pushCurrentHistoryState();
      this.loadBoardAfterMetricSeen(this.findMetric('companies', status));
      return;
    }

    if (section === 'orders') {
      this.replaceCurrentHistoryState();
      this.activeSection.set('orders');
      this.orderStatus.set(status);
      this.selectedCompany.set(null);
      this.pageNumber.set(0);
      this.mobileMenuOpen.set(false);
      this.pushCurrentHistoryState();
      this.loadBoardAfterMetricSeen(this.findMetric('orders', status));
    }
  }

  openMetric(metric: ManagerMetric): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set(metric.section);
    this.selectedCompany.set(null);

    if (metric.section === 'companies') {
      this.companyStatus.set(metric.status);
    } else {
      this.orderStatus.set(metric.status === 'Восстановления готовы' ? 'Все' : metric.status);
    }

    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(metric);
  }

  statusOptionLabel(section: ManagerSection, status: string): string {
    const metric = this.findMetric(section, status);
    return managerStatusOptionLabel(status, metric?.value ?? null, metric?.delta ?? 0);
  }

  search(): void {
    this.clearSearchTimer();
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  onKeywordChange(value: string): void {
    this.keyword.set(value);
    this.scheduleSearch();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  private scheduleSearch(): void {
    this.clearSearchTimer();
    this.searchTimer = window.setTimeout(() => {
      this.searchTimer = null;
      this.search();
    }, this.searchDelayMs);
  }

  private clearSearchTimer(): void {
    if (this.searchTimer === null) {
      return;
    }
    window.clearTimeout(this.searchTimer);
    this.searchTimer = null;
  }

  changePageSize(value: string | number): void {
    this.pageSize.set(Number(value));
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  toggleSortDirection(): void {
    this.sortDirection.update((direction) => direction === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  previousPage(): void {
    if (this.currentPage().first) {
      return;
    }

    this.pageNumber.update((page) => Math.max(page - 1, 0));
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  nextPage(): void {
    if (this.currentPage().last) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  openMobileStatusSheet(): void {
    this.mobileStatusSheetOpen.set(true);
  }

  closeMobileStatusSheet(): void {
    this.mobileStatusSheetOpen.set(false);
  }

  selectMobileStatus(status: string): void {
    this.closeMobileStatusSheet();
    if (status === this.activeStatus()) {
      return;
    }
    this.setStatus(status);
  }

  private handleMobileNavIntent(): void {
    const intent = this.mobileNavIntent.intent();
    if (!intent || intent.stamp === this.lastMobileNavIntentStamp || intent.tab !== this.activeSection()) {
      return;
    }

    this.lastMobileNavIntentStamp = intent.stamp;
    untracked(() => {
      if (intent.mode === 'menu') {
        this.openMobileStatusSheet();
      } else {
        this.closeMobileStatusSheet();
        this.setStatus('Все');
      }
      this.mobileNavIntent.clear(intent.stamp);
    });
  }

  private applyInitialMobileNavIntent(): void {
    const intent = this.mobileNavIntent.intent();
    if (!intent || intent.tab !== this.activeSection()) {
      return;
    }

    this.lastMobileNavIntentStamp = intent.stamp;
    if (intent.mode === 'menu') {
      this.openMobileStatusSheet();
      this.mobileNavIntent.clear(intent.stamp);
      return;
    }

    if (this.activeSection() === 'companies') {
      this.companyStatus.set('Все');
    } else {
      this.orderStatus.set('Все');
    }
    this.selectedCompany.set(null);
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.mobileNavIntent.clear(intent.stamp);
  }

  async copyPromo(item: PromoItem): Promise<void> {
    await this.copyText(item.text, item.label, `${item.label} скопирован`);
  }

  async copyPhone(phone?: string): Promise<void> {
    await this.copyText(phoneDigits(phone), 'телефон', 'Телефон скопирован');
  }

  async copyOrderText(order: OrderCardItem, kind: 'review' | 'payment'): Promise<void> {
    if (order.commonInvoice) {
      const url = order.commonInvoicePublicUrl || '';
      await this.copyText(
        url,
        `${kind}-${order.id}`,
        kind === 'payment' ? 'Ссылка общего счета скопирована' : 'Ссылка состава скопирована'
      );
      return;
    }

    if (kind === 'review') {
      await this.copyText(
        managerOrderReviewCopyText(order, this.board()?.promoTexts ?? []),
        `review-${order.id}`,
        'Текст проверки скопирован'
      );
      return;
    }

    try {
      const response = await firstValueFrom(this.paymentsApi.createOrderPaymentLink(order.id));
      const paymentText = response.copyText || response.url;
      await this.copyText(
        paymentText,
        `payment-${order.id}`,
        'Текст счета скопирован',
        'Счет создан. Если iPhone не дал скопировать, нажмите "счет" еще раз.'
      );
    } catch (err) {
      const message = this.errorMessage(err, 'Не удалось создать ссылку на оплату');
      this.toastService.error('Счет не создан', message);
    }
  }

  openCompanyOrders(company: CompanyCardItem): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set('Все');
    this.selectedCompany.set({
      id: company.id,
      title: company.title || `Компания #${company.id}`
    });
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoard();
  }

  openAllOrders(): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set('Все');
    this.selectedCompany.set(null);
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric('orders', 'Все'));
  }

  openCompanyEdit(company: CompanyCardItem): void {
    this.companyFacade.openCompanyEdit(company);
  }

  handleChatBotInviteOpened(company: CompanyCardItem): void {
    this.repairChatBinding(company.id, company.title, company);
  }

  handleOrderChatBotInviteOpened(order: OrderCardItem): void {
    if (!order.companyId) {
      return;
    }

    this.repairChatBinding(order.companyId, order.companyTitle, order);
  }

  openChatLinkEditor(companyId: number | null | undefined, title: string | null | undefined, urlChat: string): void {
    if (!companyId) {
      this.toastService.error('Компания не найдена', 'У карточки отсутствует ID компании');
      return;
    }
    this.chatLinkDraft.set(urlChat || '');
    this.chatLinkEditor.set({ companyId, title: title || `Компания #${companyId}` });
  }

  closeChatLinkEditor(): void {
    if (this.chatLinkSaving()) {
      return;
    }
    this.chatLinkEditor.set(null);
    this.chatLinkDraft.set('');
  }

  saveChatLink(): void {
    const editor = this.chatLinkEditor();
    if (!editor || this.chatLinkSaving()) {
      return;
    }
    const urlChat = this.chatLinkDraft().trim();
    this.chatLinkSaving.set(true);
    this.managerApi.updateCompanyChatLink(editor.companyId, urlChat).subscribe({
      next: (response) => {
        this.applyChatBindingRepair(response);
        this.chatLinkSaving.set(false);
        this.chatLinkEditor.set(null);
        this.chatLinkDraft.set('');
        if (response.repaired) {
          this.toastService.success('Ссылка сохранена', 'Группа уже привязана');
        } else {
          this.toastService.warning('Ссылка сохранена', response.message || 'Нажмите «Починить», чтобы завершить привязку');
        }
        this.loadBoard();
      },
      error: (err) => {
        this.chatLinkSaving.set(false);
        this.toastService.error('Ссылка не сохранена', this.errorMessage(err, 'Не удалось обновить ссылку на чат'));
      }
    });
  }

  private repairChatBinding(companyId: number, title: string | null | undefined, item: CompanyCardItem | OrderCardItem): void {
    const platform = managerChatBotInviteKind(item);
    const fallbackUrl = this.chatBindingFallbackUrl(item);
    const popup = fallbackUrl ? window.open('about:blank', '_blank') : null;
    if (popup) {
      popup.opener = null;
    }

    this.managerApi.repairCompanyChatBinding(companyId).subscribe({
      next: (response) => {
        this.applyChatBindingRepair(response);
        const actualPlatform = platform ?? this.chatBindingRepairPlatform(response);
        const launchUrl = safeHttpsExternalUrl(response.launchUrl) ?? safeHttpsExternalUrl(fallbackUrl) ?? '';
        if (response.repaired) {
          if (popup && !popup.closed) {
            popup.close();
          }
          this.clearChatBotLinkPoll(companyId);
          this.toastService.success(
            actualPlatform ? this.chatBotLinkedTitle(actualPlatform) : 'Группа привязана',
            title || response.companyTitle || `Компания #${companyId}`
          );
          return;
        }

        if (launchUrl) {
          if (popup && !popup.closed) {
            popup.location.href = launchUrl;
          } else {
            window.open(launchUrl, '_blank', 'noopener');
          }
          if (actualPlatform) {
            this.startChatBotLinkPoll(companyId, title, item, actualPlatform, launchUrl);
          }
          return;
        }

        if (popup && !popup.closed) {
          popup.close();
        }
        this.toastService.warning('Чат не привязан', response.message || 'Не удалось подготовить ссылку привязки');
      },
      error: (err) => {
        if (popup && !popup.closed) {
          popup.close();
        }
        this.toastService.error('Чат не починен', this.errorMessage(err, 'Не удалось проверить привязку чата'));
      }
    });
  }

  private applyChatBindingRepair(response: CompanyChatBindingRepair): void {
    this.patchBoard((board) => ({
      ...board,
      companies: {
        ...board.companies,
        content: (board.companies.content ?? []).map((company) => company.id === response.companyId
          ? this.patchCompanyChatBinding(company, response)
          : company
        )
      },
      orders: {
        ...board.orders,
        content: (board.orders.content ?? []).map((order) => order.companyId === response.companyId
          ? this.patchOrderChatBinding(order, response)
          : order
        )
      }
    }));
  }

  private patchCompanyChatBinding(company: CompanyCardItem, response: CompanyChatBindingRepair): CompanyCardItem {
    return {
      ...company,
      urlChat: response.urlChat || company.urlChat,
      groupId: response.groupId ?? company.groupId,
      telegramGroupChatId: response.telegramGroupChatId,
      telegramGroupLinked: response.telegramGroupChatId != null,
      telegramBotInviteUrl: response.telegramBotInviteUrl ?? company.telegramBotInviteUrl,
      maxGroupChatId: response.maxGroupChatId,
      maxGroupLinked: response.maxGroupChatId != null,
      maxBotInviteUrl: response.maxBotInviteUrl ?? company.maxBotInviteUrl
    };
  }

  private patchOrderChatBinding(order: OrderCardItem, response: CompanyChatBindingRepair): OrderCardItem {
    return {
      ...order,
      companyUrlChat: response.urlChat || order.companyUrlChat,
      groupId: response.groupId ?? order.groupId,
      telegramGroupChatId: response.telegramGroupChatId,
      telegramGroupLinked: response.telegramGroupChatId != null,
      telegramBotInviteUrl: response.telegramBotInviteUrl ?? order.telegramBotInviteUrl,
      maxGroupChatId: response.maxGroupChatId,
      maxGroupLinked: response.maxGroupChatId != null,
      maxBotInviteUrl: response.maxBotInviteUrl ?? order.maxBotInviteUrl
    };
  }

  private chatBindingRepairPlatform(response: CompanyChatBindingRepair): ChatBotLinkPlatform | null {
    return response.platform === 'telegram' || response.platform === 'max' ? response.platform : null;
  }

  private chatBindingFallbackUrl(item: CompanyCardItem | OrderCardItem): string {
    const inviteUrl = managerChatBotInviteUrl(item);
    if (inviteUrl) {
      return inviteUrl;
    }

    return 'title' in item ? (item.urlChat ?? '').trim() : (item.companyUrlChat ?? '').trim();
  }

  private startChatBotLinkPoll(
    companyId: number,
    title: string | null | undefined,
    item: CompanyCardItem | OrderCardItem,
    platformOverride?: ChatBotLinkPlatform,
    inviteUrlOverride?: string
  ): void {
    if (!this.itemNeedsChatBot(item) && !inviteUrlOverride) {
      return;
    }

    const platform = platformOverride ?? managerChatBotInviteKind(item);
    if (!platform) {
      return;
    }
    const inviteUrl = inviteUrlOverride ?? managerChatBotInviteUrl(item);

    const alreadyWaiting = this.chatBotLinkPolls.has(companyId);
    this.chatBotLinkPolls.set(companyId, { startedAt: Date.now(), platform });
    this.scheduleChatBotLinkRefresh(companyId, this.chatBotLinkPollDelayMs);

    if (!alreadyWaiting) {
      this.showChatBotInviteToast(platform, inviteUrl, title || `Компания #${companyId}`);
    }
  }

  openManualCompanyCreate(): void {
    this.companyCreateContext.set({ source: 'manual', leadId: null });
  }

  closeCompanyCreate(): void {
    this.companyCreateContext.set(null);
  }

  handleCompanyCreated(result: CompanyCreateResult): void {
    this.closeCompanyCreate();
    this.toastService.success('Компания создана', `${result.title} добавлена в работу`);
    this.companyDeepReportLaunch.handleCompanyCreated(result);
    this.replaceCurrentHistoryState();
    this.activeSection.set('companies');
    this.companyStatus.set('Новая');
    this.selectedCompany.set(null);
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoard();
  }

  closeCompanyEdit(): void {
    this.companyFacade.closeCompanyEdit();
  }

  handleCompanyEditDraftChange(change: ManagerCompanyEditDraftChange): void {
    this.companyFacade.handleCompanyEditDraftChange(change);
  }

  changeCompanyCategory(categoryId: number | null): void {
    this.companyFacade.changeCompanyCategory(categoryId);
  }

  saveCompanyEdit(): void {
    this.companyFacade.saveCompanyEdit();
  }

  deleteCompanyWorker(worker: ManagerOption): void {
    this.companyFacade.deleteCompanyWorker(worker);
  }

  deleteCompanyFilial(filialId: number, title: string): void {
    this.companyFacade.deleteCompanyFilial(filialId, title);
  }

  restoreCompanyFilial(filialId: number): void {
    this.companyFacade.restoreCompanyFilial(filialId);
  }

  updateCompanyFilial(request: ManagerCompanyFilialUpdateRequest): void {
    this.companyFacade.updateCompanyFilial(request);
  }

  selectCompanyBillingAccount(accountId: number | null): void {
    this.companyFacade.selectCompanyBillingAccount(accountId);
  }

  handleCompanyBillingDraftChange(change: ManagerCompanyBillingDraftChange): void {
    this.companyFacade.handleCompanyBillingDraftChange(change);
  }

  createCompanyBillingAccount(): void {
    this.companyFacade.createCompanyBillingAccount();
  }

  saveCompanyBillingAccount(): void {
    this.companyFacade.saveCompanyBillingAccount();
  }

  connectCurrentCompanyToBillingAccount(accountId: number): void {
    this.companyFacade.connectCurrentCompanyToBillingAccount(accountId);
  }

  removeCompanyFromBillingAccount(companyId: number): void {
    this.companyFacade.removeCompanyFromBillingAccount(companyId);
  }

  onCompanyBillingSearchChange(value: string): void {
    this.companyFacade.onBillingCompanySearchChange(value);
  }

  addCompanyToBillingAccount(company: CompanyCardItem): void {
    this.companyFacade.addCompanyToBillingAccount(company);
  }

  openCompanyOrderCreate(company: CompanyCardItem): void {
    this.orderFacade.openCompanyOrderCreate(company);
  }

  closeCompanyOrderCreate(): void {
    this.orderFacade.closeCompanyOrderCreate();
  }

  handleCreateOrderDraftChange(change: ManagerCreateOrderDraftChange): void {
    this.orderFacade.handleCreateOrderDraftChange(change);
  }

  createCompanyOrder(): void {
    this.orderFacade.createCompanyOrder();
  }

  openOrderEdit(order: OrderCardItem): void {
    if (order.commonInvoice) {
      void this.router.navigateByUrl(`/manager/common-billing?invoiceId=${order.commonInvoiceId ?? Math.abs(order.id)}`);
      return;
    }

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

  canManagePaperInvoices(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  markPaperInvoiceIssued(): void {
    this.orderFacade.markPaperInvoiceIssued();
  }

  updateCompanyStatus(company: CompanyCardItem, action: StatusAction): void {
    this.actionFacade.updateCompanyStatus(company, action);
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    if (order.commonInvoice) {
      this.updateCommonInvoiceStatus(order, action);
      return;
    }

    this.actionFacade.updateOrderStatus(order, action);
  }


  closeManualCardPayment(): void {
    this.manualCardPaymentOrder.set(null);
  }

  completeManualCardPayment(result: ManagerManualCardPaymentCompleted): void {
    const order = this.manualCardPaymentOrder();
    this.manualCardPaymentOrder.set(null);
    if (result.result.status === 'OWNER_APPROVAL_PENDING') {
      this.toastService.warning(
        'Ожидается подтверждение владельца',
        result.result.message
      );
      this.loadBoard();
      return;
    }
    this.toastService.success(
      'Оплата отмечена',
      `${order?.companyTitle || `Заказ #${result.context.orderId}`}: получатель — ${this.manualCardPaymentRecipientLabel(result)}`
    );
    this.loadBoard();
  }

  private manualCardPaymentRecipientLabel(result: ManagerManualCardPaymentCompleted): string {
    const prefix = result.recipient.recipientType === 'OWNER'
      ? 'Владелец'
      : result.recipient.recipientType === 'MANAGER' ? 'Менеджер' : 'Специалист';
    return result.recipient.displayName?.trim() ? `${prefix} · ${result.recipient.displayName.trim()}` : prefix;
  }
  closeCommonManualPayment(): void {
    this.commonManualPayment.set(null);
    this.mutationKey.set(null);
  }

  completeCommonManualPayment(details: CommonInvoiceDetailsResponse): void {
    const context = this.commonManualPayment();
    this.commonManualPayment.set(null);
    this.mutationKey.set(null);
    if (context) {
      this.completeCommonInvoicePaid(details, context.order);
    }
  }

  private completeCommonInvoicePaid(details: CommonInvoiceDetailsResponse, order: OrderCardItem): void {
    this.toastService.success(
      'Общий счет обновлен',
      `${order.companyTitle}: ${this.commonInvoiceStatusLabel(details, 'Оплачено')}`
    );
    this.loadBoard();
  }

  private updateCommonInvoiceStatus(order: OrderCardItem, action: StatusAction): void {
    const invoiceId = order.commonInvoiceId ?? Math.abs(order.id);
    const key = `order-${order.id}-${action.status}`;
    this.mutationKey.set(key);
    if (action.status === 'Оплачено') {
      this.commonInvoicePaymentFacade.start(order, invoiceId);
      return;
    }
    if (action.status === 'Архив') {
      this.archiveCommonInvoice(invoiceId, order, key);
      return;
    }

    const request = (() => {
      switch (action.status) {
        case 'Выставлен счет':
          return this.commonBillingApi.sendInvoice(invoiceId);
        case 'Напоминание':
          return this.commonBillingApi.remind(invoiceId);
        case 'Не оплачено':
          return this.commonBillingApi.markUnpaid(invoiceId);
        case 'Бан':
          return this.commonBillingApi.markBan(invoiceId);
        default:
          return null;
      }
    })();

    if (!request) {
      this.mutationKey.set(null);
      return;
    }

    request.subscribe({
      next: (details) => {
        this.mutationKey.set(null);
        this.toastService.success(
          'Общий счет обновлен',
          `${order.companyTitle}: ${this.commonInvoiceStatusLabel(details, action.status)}`
        );
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Общий счет не обновлен', this.errorMessage(err, 'Не удалось изменить общий счет'));
      }
    });
  }

  private archiveCommonInvoice(invoiceId: number, order: OrderCardItem, mutationKey: string): void {
    this.commonBillingApi.archivePreview(invoiceId).subscribe({
      next: (preview) => {
        if (!preview.allowed) {
          this.mutationKey.set(null);
          this.toastService.error(
            'Общий счет нельзя архивировать',
            preview.blockers.join('; ') || 'Проверьте статусы заказов внутри счета.'
          );
          return;
        }
        const confirmed = window.confirm(
          `Перевести общий счёт №${invoiceId} и все заказы внутри (${preview.totalOrders}) в архив?`
        );
        if (!confirmed) {
          this.mutationKey.set(null);
          return;
        }
        this.mutationKey.set(mutationKey);
        this.commonBillingApi.archiveInvoice(invoiceId).subscribe({
          next: () => {
            this.mutationKey.set(null);
            this.toastService.success(
              'Общий счет архивирован',
              `${order.companyTitle}: архивировано заказов — ${preview.totalOrders}`
            );
            this.loadBoard();
          },
          error: (err) => {
            this.mutationKey.set(null);
            this.toastService.error(
              'Общий счет не архивирован',
              this.errorMessage(err, 'Не удалось архивировать общий счет')
            );
          }
        });
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error(
          'Проверка архивирования не выполнена',
          this.errorMessage(err, 'Не удалось проверить общий счет')
        );
      }
    });
  }

  private requestCommonInvoiceManualPaymentEvidence(
    invoiceId: number
  ): ManualPaymentConfirmationRequest | null {
    if (!window.confirm(`Отметить общий счёт №${invoiceId} оплаченным вручную?`)) {
      return null;
    }
    const comment = window.prompt(
      'Комментарий к ручной оплате (например: «сверено по выписке»). Если есть только чек — оставьте пустым:'
    );
    if (comment === null) {
      return null;
    }
    const receiptUrl = window.prompt(
      'Ссылка на чек или платёжный документ (необязательно, если заполнен комментарий):'
    );
    if (receiptUrl === null) {
      return null;
    }
    const evidence = { comment: comment.trim(), receiptUrl: receiptUrl.trim() };
    if (!evidence.comment && !evidence.receiptUrl) {
      this.toastService.error(
        'Оплата не подтверждена',
        'Укажите комментарий или ссылку на чек.'
      );
      return null;
    }
    return evidence;
  }

  private commonInvoiceStatusLabel(details: CommonInvoiceDetailsResponse, fallback: string): string {
    switch (details.summary?.status) {
      case 'COLLECTING':
        return 'Ожидает общего счета';
      case 'READY':
        return 'Опубликовано';
      case 'INVOICED':
        return 'Выставлен счет';
      case 'REMINDER':
      case 'PARTIALLY_PAID':
        return 'Напоминание';
      case 'NEEDS_ATTENTION':
        return 'Требует внимания';
      case 'PAID':
        return 'Оплачено';
      case 'UNPAID':
        return 'Не оплачено';
      case 'BAN':
        return 'Бан';
      default:
        return fallback;
    }
  }

  toggleOrderClientWaiting(order: OrderCardItem): void {
    this.actionFacade.toggleOrderClientWaiting(order);
  }

  closeOverdueModal(): void {
    this.overdueModalOpen.set(false);
  }

  openOverdueStatus(status: string): void {
    this.closeOverdueModal();
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set(status || 'Все');
    this.selectedCompany.set(null);
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric('orders', this.orderStatus()));
  }

  saveCompanyCardNote(company: CompanyCardItem, value: string): void {
    this.actionFacade.saveCompanyCardNote(company, value);
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    this.actionFacade.saveOrderCompanyNote(order, value);
  }

  saveOrderCardNote(order: OrderCardItem, value: string): void {
    this.actionFacade.saveOrderCardNote(order, value);
  }

  orderActions(order: OrderCardItem): StatusAction[] {
    const privileged = this.canForceBan();
    const showPublicationExitActions = privileged && order.status === 'Публикация';
    return managerOrderActions(order, showPublicationExitActions, privileged);
  }

  private canForceBan(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  trackStatus(_index: number, status: string): string {
    return trackManagerStatus(_index, status);
  }

  trackCompany(_index: number, company: CompanyCardItem): number {
    return trackManagerCompany(_index, company);
  }

  trackOrder(_index: number, order: OrderCardItem): number {
    return trackManagerOrder(_index, order);
  }

  trackMetric(_index: number, metric: ManagerMetric): string {
    return trackManagerMetric(_index, metric);
  }

  trackOverdueStatus(_index: number, status: ManagerOverdueStatus): string {
    return status.status;
  }

  overdueMaxDays(summary: ManagerOverdueOrders): number {
    return summary.statuses.reduce((max, status) => Math.max(max, status.maxDays), 0);
  }

  private findMetric(section: ManagerSection, status: string): ManagerMetric | undefined {
    return this.board()?.metrics.find((item) => item.section === section && item.status === status);
  }

  private loadBoardAfterMetricSeen(metric?: ManagerMetric): void {
    if (!metric) {
      this.loadBoard();
      return;
    }

    this.metricSnapshotApi.markSeen({
      page: 'manager',
      section: metric.section,
      status: metric.status,
      value: metric.value
    }).subscribe({
      next: () => {
        this.clearMetricDelta(metric);
        this.loadBoard();
      },
      error: () => this.loadBoard()
    });
  }

  private clearMetricDelta(metric: ManagerMetric): void {
    this.patchBoard((board) => ({
      ...board,
      metrics: board.metrics.map((item) => item.section === metric.section && item.status === metric.status
        ? { ...item, delta: 0 }
        : item
      )
    }));
  }

  private captureHistoryView(): ManagerHistoryView {
    return {
      activeSection: this.activeSection(),
      companyStatus: this.companyStatus(),
      orderStatus: this.orderStatus(),
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection(),
      selectedCompany: this.selectedCompany(),
      managerId: this.selectedManagerId(),
      control: this.selectedControl()
    };
  }

  private applyHistoryView(view: ManagerHistoryView): void {
    this.activeSection.set(view.activeSection);
    this.companyStatus.set(view.companyStatus);
    this.orderStatus.set(view.orderStatus);
    this.keyword.set(view.keyword);
    this.pageNumber.set(view.pageNumber);
    this.pageSize.set(view.pageSize);
    this.sortDirection.set(view.sortDirection);
    this.selectedCompany.set(view.selectedCompany);
    this.selectedManagerId.set(view.managerId);
    this.selectedControl.set(view.control);
    this.mobileMenuOpen.set(false);
  }

  private replaceCurrentHistoryState(): void {
    const view = this.captureHistoryView();
    const state = managerWithHistoryState(window.history.state, view, this.historyStateKey);
    window.history.replaceState(state, document.title, this.managerUrlForView(view));
  }

  private pushCurrentHistoryState(): void {
    const view = this.captureHistoryView();
    const state = managerWithHistoryState(window.history.state, view, this.historyStateKey);
    window.history.pushState(state, document.title, this.managerUrlForView(view));
  }

  private managerUrlForView(view: ManagerHistoryView): string {
    return this.router.serializeUrl(this.router.createUrlTree([this.managerRouteForSection(view.activeSection)], {
      queryParams: managerViewQueryParams(view)
    }));
  }

  private routeManagerSection(): ManagerSection | null {
    const section = this.route.snapshot.data['managerSection'];
    return section === 'orders' || section === 'companies' ? section : null;
  }

  private managerRouteForSection(section: ManagerSection): string {
    return section === 'orders' ? '/orders' : '/companies';
  }

  private async copyText(
    text: string,
    copiedKey: string,
    toast: string,
    failureToast = 'Браузер не дал доступ к буферу обмена'
  ): Promise<boolean> {
    const value = text.trim();

    if (!value) {
      return false;
    }

    if (await copyTextToClipboard(value)) {
      this.copied.set(copiedKey);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set(null);
        }
      }, 1200);
      return true;
    }

    this.toastService.error('Не скопировано', failureToast);
    return false;
  }

  private openCreatedCompanyOrders(companyId: number, companyTitle: string): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set('Все');
    this.selectedCompany.set({
      id: companyId,
      title: companyTitle || `Компания #${companyId}`
    });
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoard();
  }

  private patchBoard(updater: (board: ManagerBoard) => ManagerBoard): void {
    this.board.update((board) => board ? updater(board) : board);
  }

  private reloadBoardForChatBotLinks(): void {
    if (this.chatBotLinkRefreshInFlight) {
      return;
    }

    this.chatBotLinkRefreshInFlight = true;
    this.managerApi.getBoard({
      section: this.activeSection(),
      status: this.activeStatus(),
      keyword: this.keyword(),
      companyId: this.activeSection() === 'orders' ? this.selectedCompany()?.id : undefined,
      managerId: this.activeSection() === 'orders' ? this.selectedManagerId() : null,
      control: this.activeSection() === 'orders' ? this.selectedControl() : null,
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection()
    }).subscribe({
      next: (board) => {
        this.board.set(board);
        this.chatBotLinkRefreshInFlight = false;
        this.updateChatBotLinkPolls(board);
      },
      error: () => {
        this.chatBotLinkRefreshInFlight = false;
        this.reschedulePendingChatBotLinkPolls();
      }
    });
  }

  private updateChatBotLinkPolls(board: ManagerBoard): void {
    const now = Date.now();

    for (const [companyId, poll] of this.chatBotLinkPolls.entries()) {
      const item = this.visibleChatBotItem(board, companyId);
      if (item && !this.itemNeedsChatBot(item)) {
        this.clearChatBotLinkPoll(companyId);
        this.toastService.success(this.chatBotLinkedTitle(poll.platform), this.chatBotItemTitle(item, companyId));
        continue;
      }

      if (now - poll.startedAt >= this.chatBotLinkPollTimeoutMs) {
        this.clearChatBotLinkPoll(companyId);
        continue;
      }

      this.scheduleChatBotLinkRefresh(companyId, this.chatBotLinkPollDelayMs);
    }
  }

  private reschedulePendingChatBotLinkPolls(): void {
    for (const companyId of this.chatBotLinkPolls.keys()) {
      this.scheduleChatBotLinkRefresh(companyId, this.chatBotLinkPollDelayMs);
    }
  }

  private scheduleChatBotLinkRefresh(companyId: number, delayMs: number): void {
    const existingTimer = this.chatBotLinkPollTimers.get(companyId);
    if (existingTimer) {
      window.clearTimeout(existingTimer);
    }

    const timer = window.setTimeout(() => {
      this.chatBotLinkPollTimers.delete(companyId);
      if (this.chatBotLinkPolls.has(companyId)) {
        this.reloadBoardForChatBotLinks();
      }
    }, delayMs);
    this.chatBotLinkPollTimers.set(companyId, timer);
  }

  private clearChatBotLinkPoll(companyId: number): void {
    const timer = this.chatBotLinkPollTimers.get(companyId);
    if (timer) {
      window.clearTimeout(timer);
    }

    this.chatBotLinkPollTimers.delete(companyId);
    this.chatBotLinkPolls.delete(companyId);
  }

  private itemNeedsChatBot(item: CompanyCardItem | OrderCardItem): boolean {
    return Boolean((item.telegramBotInviteUrl ?? '').trim() || (item.maxBotInviteUrl ?? '').trim());
  }

  private visibleChatBotItem(board: ManagerBoard, companyId: number): CompanyCardItem | OrderCardItem | undefined {
    return (board.companies.content ?? []).find((company) => company.id === companyId)
      ?? (board.orders.content ?? []).find((order) => order.companyId === companyId);
  }

  private chatBotItemTitle(item: CompanyCardItem | OrderCardItem, companyId: number): string {
    if ('title' in item) {
      return item.title || `Компания #${companyId}`;
    }

    return item.companyTitle || `Компания #${companyId}`;
  }

  private chatBotLinkedTitle(platform: ChatBotLinkPlatform): string {
    return platform === 'max' ? 'MAX-группа привязана' : 'Telegram-группа привязана';
  }

  private showChatBotInviteToast(platform: ChatBotLinkPlatform, inviteUrl: string, title: string): void {
    if (platform === 'max') {
      const startCommand = this.maxStartCommand(inviteUrl);
      const webStartUrl = this.maxWebStartUrl(inviteUrl);
      this.toastService.warning(
        'MAX: привязка группы',
        'Сначала попробуйте основную MAX-ссылку. Если кнопка запуска не сработала, откройте этот же запуск через MAX Web или скопируйте /start.',
        [
          ...(startCommand ? [{
            label: 'Скопировать /start',
            callback: () => void this.copyText(startCommand, 'max-start', 'Команда MAX скопирована')
          }] : []),
          ...(webStartUrl ? [{
            label: 'Открыть через MAX Web',
            href: webStartUrl
          }] : []),
          {
            label: 'Скопировать ссылку',
            callback: () => void this.copyText(inviteUrl, 'max-link', 'Ссылка MAX скопирована')
          }
        ]
      );
      return;
    }

    this.toastService.info(
      'Жду привязку Telegram',
      `После выбора группы карточка "${title}" обновится сама, текущая страница останется на месте.`,
      [{
        label: 'Скопировать ссылку',
        callback: () => void this.copyText(inviteUrl, 'telegram-link', 'Ссылка Telegram скопирована')
      }]
    );
  }

  private maxStartCommand(inviteUrl: string): string {
    try {
      const url = new URL(inviteUrl);
      const payload = (url.searchParams.get('start') ?? '').trim();
      return payload ? `/start ${payload}` : '';
    } catch {
      return '';
    }
  }

  private maxWebStartUrl(inviteUrl: string): string {
    try {
      const url = new URL(inviteUrl);
      url.protocol = 'https:';
      url.hostname = 'web.max.ru';
      return url.toString();
    } catch {
      return '';
    }
  }

  private errorMessage(err: unknown, fallback: string): string {
    return managerErrorMessage(err, fallback);
  }

  private loadDailyOverdueReminder(): void {
    const today = this.localDateKey();
    const storageKey = this.overdueAlertStorageKey();

    if (this.readStoredDate(storageKey) === today) {
      return;
    }

    this.managerApi.getOverdueOrders().subscribe({
      next: (summary) => {
        this.writeStoredDate(storageKey, today);
        const normalizedSummary = {
          ...summary,
          statuses: summary.statuses ?? []
        };

        if (normalizedSummary.total > 0) {
          this.overdueOrders.set(normalizedSummary);
          this.overdueModalOpen.set(true);
        }
      },
      error: () => {
        // The reminder is helpful, but the board itself should not fail because of it.
      }
    });
  }

  private overdueAlertStorageKey(): string {
    const token = this.auth.tokenParsed() as { preferred_username?: string; sub?: string } | undefined;
    const userKey = token?.preferred_username || token?.sub || 'user';
    return `${this.overdueAlertStorageKeyPrefix}:${userKey}`;
  }

  private localDateKey(date = new Date()): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private readStoredDate(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeStoredDate(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Storage can be blocked in private mode; the reminder will simply try again later.
    }
  }
}
